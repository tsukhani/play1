package play.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Unified front for "submit/schedule a task" that hides the platform-vs-virtual-thread
 * choice from callers. {@link play.Invoker} and {@link play.jobs.JobsPlugin} each hold
 * one of these and route every dispatch through it, eliminating the {@code
 * if (usingVirtualThreads) virtualExecutor.submit(...) else executor.submit(...)} branch
 * that previously appeared at every call site.
 *
 * <p>The (platform-executor, virtual-executor) pair is held as a single immutable
 * {@link State} read once per submit/schedule call. Without an atomic snapshot, a
 * concurrent {@link #shutdownNow} could clear the executor reference while a submitter
 * still observed the previous mode, dereferencing {@code null}; with the snapshot the
 * submit either sees a fully-populated state or the empty state and rejects cleanly.</p>
 */
public final class ExecutorFacade {

    // L1: the previous record carried a redundant boolean virtualMode that duplicated
    // information already encoded in (virtual != null) vs (platform != null). The dispatch
    // logic never read it — only isUsingVirtualThreads() did — so it was a state-coherency
    // hazard for no benefit. The two reference fields are now the single source of truth.
    private record State(ScheduledThreadPoolExecutor platform,
                         VirtualThreadScheduledExecutor virtual) {
        static final State EMPTY = new State(null, null);
    }

    private volatile State state = State.EMPTY;

    /**
     * Install a virtual-thread-backed scheduler. Replaces any previous executor.
     * Caller is responsible for shutting down the old one if needed (typically via
     * {@link #shutdownNow()} before this call).
     */
    public synchronized void useVirtual(VirtualThreadScheduledExecutor virtual) {
        state = new State(null, virtual);
    }

    /**
     * Install a platform-thread-backed scheduler. Replaces any previous executor.
     */
    public synchronized void usePlatform(ScheduledThreadPoolExecutor platform) {
        state = new State(platform, null);
    }

    public boolean isUsingVirtualThreads() {
        return state.virtual != null;
    }

    /** May be null if the facade is using virtual threads or has not been initialized. */
    public ScheduledThreadPoolExecutor platformExecutor() {
        return state.platform;
    }

    /** May be null if the facade is using platform threads or has not been initialized. */
    public VirtualThreadScheduledExecutor virtualExecutor() {
        return state.virtual;
    }

    public Future<?> submit(Runnable task) {
        State s = state;
        if (s.virtual != null) return s.virtual.submit(task);
        if (s.platform != null) return s.platform.submit(task);
        throw new RejectedExecutionException("ExecutorFacade has been shut down");
    }

    public <V> Future<V> submit(Callable<V> task) {
        State s = state;
        if (s.virtual != null) return s.virtual.submit(task);
        if (s.platform != null) return s.platform.submit(task);
        throw new RejectedExecutionException("ExecutorFacade has been shut down");
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        State s = state;
        if (s.virtual != null) return s.virtual.schedule(command, delay, unit);
        if (s.platform != null) return s.platform.schedule(command, delay, unit);
        throw new RejectedExecutionException("ExecutorFacade has been shut down");
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        State s = state;
        if (s.virtual != null) return s.virtual.schedule(callable, delay, unit);
        if (s.platform != null) return s.platform.schedule(callable, delay, unit);
        throw new RejectedExecutionException("ExecutorFacade has been shut down");
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        State s = state;
        if (s.virtual != null) return s.virtual.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        if (s.platform != null) return s.platform.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        throw new RejectedExecutionException("ExecutorFacade has been shut down");
    }

    /**
     * Shut down whichever executor is currently active and clear the state atomically.
     * After this returns, every submit/schedule throws {@link RejectedExecutionException}
     * until {@link #useVirtual} or {@link #usePlatform} installs a new executor.
     */
    public synchronized void shutdownNow() {
        State s = state;
        state = State.EMPTY;
        if (s.platform != null) s.platform.shutdownNow();
        if (s.virtual != null) s.virtual.shutdownNow();
    }

    /**
     * Audit M27: orderly shutdown — block accepting new work, wait up to
     * {@code timeoutMs} for in-flight tasks to complete, then escalate to
     * {@link #shutdownNow} if anything is still running. Lets background jobs
     * mid-DB-transaction commit cleanly during a hot reload or app stop instead
     * of being interrupted and corrupting data.
     *
     * @return true if everything terminated within the timeout, false if
     *         shutdownNow had to fire.
     */
    public synchronized boolean shutdownGracefully(long timeoutMs) {
        State s = state;
        state = State.EMPTY;
        boolean clean = true;
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            if (s.platform != null) {
                s.platform.shutdown();
                long remaining = Math.max(0L, deadline - System.nanoTime());
                if (!s.platform.awaitTermination(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    s.platform.shutdownNow();
                    clean = false;
                }
            }
            if (s.virtual != null) {
                s.virtual.shutdown();
                long remaining = Math.max(0L, deadline - System.nanoTime());
                if (!s.virtual.awaitTermination(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    s.virtual.shutdownNow();
                    clean = false;
                }
            }
        } catch (InterruptedException ie) {
            if (s.platform != null) s.platform.shutdownNow();
            if (s.virtual != null) s.virtual.shutdownNow();
            Thread.currentThread().interrupt();
            clean = false;
        }
        return clean;
    }
}
