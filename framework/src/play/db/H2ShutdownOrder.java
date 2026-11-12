package play.db;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import play.Logger;
import play.Play;

/**
 * PF-201: H2 closes the embedded databases it has open from a JVM shutdown hook of its own,
 * {@code org.h2.engine.OnExitDatabaseCloser}. At SIGTERM the JVM starts every hook at once, so that
 * one closed the database while {@code Play.shutdownHook} was still in {@code stop()}, and an
 * {@code @OnApplicationStop} job that wrote reopened it: a second lock, a second AUTO_SERVER
 * listener, a second compaction. {@code DB_CLOSE_ON_EXIT=FALSE} is no way out: H2 refuses it
 * together with {@code AUTO_SERVER=TRUE}.
 *
 * <p>So, while Play's hook is registered, H2's hook is taken out of the JVM and Play's hook runs
 * H2's closer itself, after {@code stop()}. Never in {@code stop()}: a DEV reload runs that too,
 * and the closer leaves H2 marked terminated for the rest of the JVM's life.
 * <ul>
 * <li>{@link DBPlugin} takes the hook on every start, once an H2 datasource is up, and a keeper
 * takes it every second after that: H2 puts its hook back whenever a database it had closed
 * completely opens again, which a pool of one does at every {@code maxLifetime} rotation.</li>
 * <li>The closer runs at most once. When H2's hook was back by the time of the SIGTERM, the JVM has
 * started it with the others, and it is left to close the databases, as before PF-201.</li>
 * <li>A fallback hook runs the closer when Play's hook has not finished {@code play.jobs.stop.timeout}
 * plus {@link #FALLBACK_MARGIN_MS} after {@code stop()} began, so that a hook that hangs still has the
 * databases closed, before the launchers stop waiting at 35 s when no request held the server drain
 * up. The drain before {@code stop()} waits up to {@code play.shutdown.drain.timeout} for requests
 * in flight, and is not counted: a clock started at the SIGTERM would have closed H2 under the
 * stop jobs of a shutdown whose requests took most of the drain. The fallback returns as soon as
 * Play's hook finishes.</li>
 * <li>When any of the reflection fails, H2's hook stays, and one WARN says why.</li>
 * </ul>
 */
public final class H2ShutdownOrder {

    static final String CLOSER = "org.h2.engine.OnExitDatabaseCloser";

    /** Added to each of the fallback's waits: under the launchers' 35 s at the default 30 s timeouts. */
    static final long FALLBACK_MARGIN_MS = 3_000;

    private static final H2ShutdownOrder h2 = new H2ShutdownOrder(CLOSER, 1_000);

    private final String closerClass;
    private final long keeperIntervalMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopBegun = new CountDownLatch(1);
    private final CountDownLatch playHookDone = new CountDownLatch(1);

    private boolean resolved;
    /** {@code OnExitDatabaseCloser.INSTANCE}, H2's hook; null until resolved, and when that failed. */
    Thread h2Hook;
    private Method onShutdown;
    /** Why the reflection failed, or null. */
    String failure;
    private volatile boolean deferred;
    /** The fallback, once it is registered with the JVM. */
    Thread fallback;

    H2ShutdownOrder(String closerClass, long keeperIntervalMs) {
        this.closerClass = closerClass;
        this.keeperIntervalMs = keeperIntervalMs;
    }

    /** {@link DBPlugin#onApplicationStart}, on every start once an H2 datasource is up. */
    public static void deferToPlayHook() {
        if (Play.shutdownHookEnabled()) {
            h2.defer();
        }
    }

    /** {@code Play.shutdownHook}, once the server drain is over and {@code stop()} begins. */
    public static void stopBegins() {
        h2.beginStop();
    }

    /** {@code Play.shutdownHook}, after {@code stop()} and before logging stops. */
    public static void closeFromPlayHook() {
        h2.afterStop();
    }

    /** Take H2's hook out of the JVM; true when Play's hook will close H2's databases. */
    synchronized boolean defer() {
        if (!resolve()) {
            return false;
        }
        if (take() && !deferred) {
            deferred = true;
            // Neither may hold the class loader of a DEV start, which a reload replaces: as Play's hook
            fallback = new Thread(() -> awaitPlayHook(drainBudgetMs(), stopBudgetMs()), "play-h2-shutdown-fallback");
            fallback.setContextClassLoader(ClassLoader.getSystemClassLoader());
            try {
                Runtime.getRuntime().addShutdownHook(fallback);
            } catch (IllegalStateException shuttingDown) {
                // Play's hook waits for this start to end, then closes them
            }
            Thread keeper = Thread.ofVirtual().name("play-h2-hook-keeper").unstarted(this::keep);
            keeper.setContextClassLoader(ClassLoader.getSystemClassLoader());
            keeper.start();
        }
        return deferred;
    }

    void beginStop() {
        stopBegun.countDown();
    }

    /** Play's hook: close H2's databases, unless the fallback or H2's own hook has. */
    void afterStop() {
        try {
            close();
        } finally {
            stopBegun.countDown();
            playHookDone.countDown();
        }
    }

    /**
     * The fallback: return as soon as Play's hook is done, or close the databases once
     * {@code stop()} has run {@code stopBudgetMs} without finishing. The clock starts when
     * {@code stop()} begins, or when the drain before it has had {@code drainBudgetMs}.
     */
    void awaitPlayHook(long drainBudgetMs, long stopBudgetMs) {
        try {
            stopBegun.await(drainBudgetMs, TimeUnit.MILLISECONDS);
            if (playHookDone.await(stopBudgetMs, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Logger.warn("Play's shutdown hook is still stopping the application after %d ms: closing the H2 databases still open",
                stopBudgetMs);
        close();
    }

    /** What the drain before {@code stop()} may take: {@code play.shutdown.drain.timeout}, as Server reads it. */
    static long drainBudgetMs() {
        return timeoutMs("play.shutdown.drain.timeout") + FALLBACK_MARGIN_MS;
    }

    /** What {@code stop()} may take: {@code play.jobs.stop.timeout}, as JobsPlugin reads it. */
    static long stopBudgetMs() {
        return timeoutMs("play.jobs.stop.timeout") + FALLBACK_MARGIN_MS;
    }

    private static long timeoutMs(String key) {
        Properties conf = Play.configuration;
        if (conf != null) {
            try {
                return Long.parseLong(conf.getProperty(key, "30000"));
            } catch (NumberFormatException e) {
                // the default, as the two readers have it
            }
        }
        return 30_000;
    }

    synchronized boolean resolve() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> closer = Class.forName(closerClass, true, H2ShutdownOrder.class.getClassLoader());
                Field instance = closer.getDeclaredField("INSTANCE");
                Method method = closer.getDeclaredMethod("onShutdown");
                if (!Modifier.isStatic(instance.getModifiers()) || !Thread.class.isAssignableFrom(instance.getType())) {
                    throw new NoSuchFieldException(closerClass + ".INSTANCE is not a static Thread");
                }
                if (!Modifier.isStatic(method.getModifiers())) {
                    throw new NoSuchMethodException(closerClass + ".onShutdown() is not static");
                }
                instance.setAccessible(true);
                method.setAccessible(true);
                Thread hook = (Thread) instance.get(null);
                if (hook == null) {
                    throw new NoSuchFieldException(closerClass + ".INSTANCE is null");
                }
                onShutdown = method;
                h2Hook = hook;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                failure = e.toString();
                Logger.warn("H2 closes its databases from its own shutdown hook, at the same time as Play's (PF-201): %s",
                        failure);
            }
        }
        return h2Hook != null;
    }

    /** False when H2's hook was not in the JVM, and once the JVM is shutting down. */
    private boolean take() {
        try {
            return Runtime.getRuntime().removeShutdownHook(h2Hook);
        } catch (IllegalStateException shuttingDown) {
            return false;
        }
    }

    /**
     * Take H2's hook again every {@code keeperIntervalMs}: a retry on starts alone lapses in
     * production. H2 re-adds its hook whenever a database it had closed completely opens again,
     * and an idle pool of one closes its database at every {@code maxLifetime} rotation (Play's
     * defaults, {@code db.pool.minSize=1} and 30 min): Hikari retires the lone connection before
     * it opens the next. Measured on 2026-11-12, HikariCP 7.1.0 and H2 2.5.252 with
     * {@code maxLifetime} at 30 s: the lock file went 27.0 s in, came back 0.1 s later, and H2's
     * hook was back in the JVM. Should a SIGTERM land before the next take, {@link #close()}
     * leaves the closing to H2's hook, as before PF-201.
     */
    private void keep() {
        try {
            while (!closed.get()) {
                Thread.sleep(keeperIntervalMs);
                take();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void close() {
        if (!deferred || !closed.compareAndSet(false, true)) {
            return;
        }
        // H2 put its hook back after a reopen the keeper had not seen yet, and the JVM, which
        // starts every hook before it waits for any, has started it: H2 closes them itself.
        if (h2Hook.getState() != Thread.State.NEW) {
            return;
        }
        try {
            onShutdown.invoke(null);
        } catch (InvocationTargetException e) {
            Logger.warn(e.getCause(), "Closing the H2 databases at shutdown failed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.warn(e, "Closing the H2 databases at shutdown failed");
        }
    }
}
