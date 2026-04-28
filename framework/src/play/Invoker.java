package play;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;

import play.Play.Mode;
import play.classloading.ApplicationClassloader;
import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.i18n.Lang;
import play.libs.F;
import play.libs.F.Promise;
import play.utils.VirtualThreadScheduledExecutor;

/**
 * Run some code in a Play! context
 */
public class Invoker {

    /**
     * Virtual-thread scheduling executor. Recreated by {@link #init()} on each
     * application start; nulled by {@link #stop()} during shutdown.
     */
    public static volatile VirtualThreadScheduledExecutor scheduler;

    /**
     * Audit H2-counter: number of invocations currently executing (queued or running).
     * Incremented at submit time, decremented in the wrapper's finally block once
     * {@link Invocation#run()} returns. Public for observability tooling
     * (e.g. PlayStatusPlugin) — do not mutate externally.
     */
    public static final AtomicLong inflightInvocations = new AtomicLong();

    /**
     * Audit H2-counter: cumulative count of invocations submitted since process start.
     * Incremented once at submit time. Public for observability tooling.
     */
    public static final AtomicLong totalInvocations = new AtomicLong();

    /**
     * Audit H2-counter: submit a Runnable through the scheduler and track it via
     * {@link #inflightInvocations} / {@link #totalInvocations}. All public dispatch
     * paths (the two {@code invoke} variants, the {@code WaitForTasksCompletion}
     * resume submits) flow through this so the counters cover platform-thread mode,
     * virtual-thread mode, immediate dispatch, delayed dispatch, and Promise-redeem
     * resume paths uniformly.
     *
     * <p>The counters are published as {@code public static final} fields above so
     * observability tooling (e.g. PlayStatusPlugin) can read them without reflection.</p>
     */
    private static Future<?> submitTracked(Runnable r) {
        totalInvocations.incrementAndGet();
        inflightInvocations.incrementAndGet();
        return scheduler.submit(() -> {
            try { r.run(); } finally { inflightInvocations.decrementAndGet(); }
        });
    }

    /**
     * Run the code in a new thread took from a thread pool.
     *
     * @param invocation
     *            The code to run
     * @return The future object, to know when the task is completed
     */
    public static Future<?> invoke(Invocation invocation) {
        ensureExecutor();
        invocation.waitInQueue = MonitorFactory.start("Waiting for execution");
        return submitTracked(invocation);
    }

    /**
     * Run the code in a new thread after a delay
     *
     * @param invocation
     *            The code to run
     * @param millis
     *            The time to wait before, in milliseconds
     * @return The future object, to know when the task is completed
     */
    public static Future<?> invoke(Invocation invocation, long millis) {
        ensureExecutor();
        // Audit H2-counter: track delayed dispatches too. Pre-increment so observability
        // is consistent with the immediate path (i.e. counted at submission time);
        // decrement after the wrapper runs.
        totalInvocations.incrementAndGet();
        inflightInvocations.incrementAndGet();
        return scheduler.schedule(() -> {
            try { invocation.run(); } finally { inflightInvocations.decrementAndGet(); }
        }, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Run the code in the same thread than caller.
     * 
     * @param invocation
     *            The code to run
     */
    public static void invokeInThread(DirectInvocation invocation) {
        boolean retry = true;
        while (retry) {
            invocation.run();
            if (invocation.retry == null) {
                retry = false;
            } else {
                try {
                    if (invocation.retry.task != null) {
                        invocation.retry.task.get();
                    } else {
                        Thread.sleep(invocation.retry.timeout);
                    }
                } catch (Exception e) {
                    throw new UnexpectedException(e);
                }
                retry = true;
            }
        }
    }

    static void resetClassloaders() {
        // Virtual threads cannot be enumerated with Thread.enumerate(). Each virtual
        // thread sets its context classloader at the start of an invocation
        // (in Invocation.init() and Invocation.before()), so stale classloaders are
        // naturally replaced on the next invocation. Nothing to do here under VT-only.
    }

    /**
     * The class/method that will be invoked by the current operation
     */
    public static class InvocationContext {

        public static final ThreadLocal<InvocationContext> current = new ThreadLocal<>();
        private final List<Annotation> annotations;
        private final String invocationType;

        public static InvocationContext current() {
            return current.get();
        }

        public InvocationContext(String invocationType) {
            this.invocationType = invocationType;
            this.annotations = new ArrayList<>();
        }

        public InvocationContext(String invocationType, List<Annotation> annotations) {
            this.invocationType = invocationType;
            this.annotations = annotations;
        }

        public InvocationContext(String invocationType, Annotation[] annotations) {
            this.invocationType = invocationType;
            this.annotations = Arrays.asList(annotations);
        }

        public InvocationContext(String invocationType, Annotation[]... annotations) {
            this.invocationType = invocationType;
            this.annotations = new ArrayList<>();
            for (Annotation[] some : annotations) {
                this.annotations.addAll(Arrays.asList(some));
            }
        }

        public List<Annotation> getAnnotations() {
            return annotations;
        }

        @SuppressWarnings("unchecked")
        public <T extends Annotation> T getAnnotation(Class<T> clazz) {
            for (Annotation annotation : annotations) {
                if (annotation.annotationType().isAssignableFrom(clazz)) {
                    return (T) annotation;
                }
            }
            return null;
        }

        public <T extends Annotation> boolean isAnnotationPresent(Class<T> clazz) {
            for (Annotation annotation : annotations) {
                if (annotation.annotationType().isAssignableFrom(clazz)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the InvocationType for this invocation - Ie: A plugin can use this to find out if it runs in the
         * context of a background Job
         * 
         * @return the InvocationType for this invocation
         */
        public String getInvocationType() {
            return invocationType;
        }

        @Override
        public String toString() {
            return "InvocationType: %s. annotations: %s".formatted(
                    invocationType,
                    annotations.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")));
        }
    }

    /**
     * An Invocation in something to run in a Play! context
     */
    public abstract static class Invocation implements Runnable {

        /**
         * If set, monitor the time the invocation waited in the queue
         */
        Monitor waitInQueue;

        /**
         * Override this method
         * 
         * @throws java.lang.Exception
         *             Thrown if Invocation encounters any problems
         */
        public abstract void execute() throws Exception;

        /**
         * Needs this method to do stuff *before* init() is executed. The different Invocation-implementations does a
         * lot of stuff in init() and they might do it before calling super.init()
         */
        protected void preInit() {
            // clear language for this request - we're resolving it later when it is needed
            Lang.clear();
        }

        /**
         * Init the call (especially useful in DEV mode to detect changes)
         * 
         * @return true if successful
         */
        public boolean init() {
            Thread.currentThread().setContextClassLoader(Play.classloader);
            Play.detectChanges();
            if (!Play.started) {
                if (Play.mode == Mode.PROD) {
                    throw new UnexpectedException("Application is not started");
                }
                Play.start();
            }
            InvocationContext.current.set(getInvocationContext());
            return true;
        }

        public abstract InvocationContext getInvocationContext();

        /**
         * Things to do before an Invocation
         */
        public void before() {
            Thread.currentThread().setContextClassLoader(Play.classloader);
            Play.pluginCollection.beforeInvocation();
        }

        /**
         * Things to do after an Invocation. (if the Invocation code has not thrown any exception)
         */
        public void after() {
            Play.pluginCollection.afterInvocation();
            LocalVariablesNamesTracer.checkEmpty(); // detect bugs ....
        }

        /**
         * Things to do when the whole invocation has succeeded (before + execute + after)
         * 
         * @throws java.lang.Exception
         *             Thrown if Invoker encounters any problems
         */
        public void onSuccess() throws Exception {
            Play.pluginCollection.onInvocationSuccess();
        }

        /**
         * Things to do if the Invocation code thrown an exception
         * 
         * @param e
         *            The exception
         */
        public void onException(Throwable e) {
            Play.pluginCollection.onInvocationException(e);
            if (e instanceof PlayException pe) {
                throw pe;
            }
            throw new UnexpectedException(e);
        }

        /**
         * The request is suspended
         * 
         * @param suspendRequest
         *            the suspended request
         */
        public void suspend(Suspend suspendRequest) {
            if (suspendRequest.task != null) {
                WaitForTasksCompletion.waitFor(suspendRequest.task, this);
            } else {
                Invoker.invoke(this, suspendRequest.timeout);
            }
        }

        /**
         * Things to do in all cases after the invocation.
         */
        public void _finally() {
            Play.pluginCollection.invocationFinally();
            InvocationContext.current.remove();
        }

        private void withinFilter(play.libs.F.Function0<Void> fct) throws Throwable {
            F.Option<PlayPlugin.Filter<Void>> filters = Play.pluginCollection.composeFilters();
            if (filters.isDefined()) {
                filters.get().withinFilter(fct);
            }
        }

        /**
         * It's time to execute.
         */
        @Override
        public void run() {
            if (waitInQueue != null) {
                waitInQueue.stop();
            }
            try {
                preInit();
                if (init()) {
                    before();
                    final AtomicBoolean executed = new AtomicBoolean(false);
                    this.withinFilter(() -> {
                        executed.set(true);
                        execute();
                        return null;
                    });
                    // No filter function found => we need to execute anyway( as before the use of withinFilter )
                    if (!executed.get()) {
                        execute();
                    }
                    after();
                    onSuccess();
                }
            } catch (Suspend e) {
                // Audit C4: do not call after() on suspend. afterInvocation must run
                // exactly once per logical request — on the resumed invocation after
                // the Suspend resolves (line above). The JPA plugin's afterInvocation
                // commits the transaction and closes the EntityManager; firing it on
                // suspend would close the EM the resumed invocation still expects to
                // use, then fire a phantom second commit when the resumed run also
                // calls after(). Plugins that need cleanup on suspend should hook
                // invocationFinally, which runs unconditionally in the finally below.
                suspend(e);
            } catch (Throwable e) {
                onException(e);
            } finally {
                _finally();
            }
        }
    }

    /**
     * A direct invocation (in the same thread than caller)
     */
    public abstract static class DirectInvocation extends Invocation {

        public static final String invocationType = "DirectInvocation";

        Suspend retry = null;

        @Override
        public boolean init() {
            retry = null;
            return super.init();
        }

        @Override
        public void suspend(Suspend suspendRequest) {
            retry = suspendRequest;
        }

        @Override
        public InvocationContext getInvocationContext() {
            return new InvocationContext(invocationType);
        }
    }

    /**
     * Ensure that a default executor exists for DEV mode lazy startup.
     * In DEV mode, the first request arrives before Play.start() has called init(),
     * so we create a minimal executor to handle that first invocation.
     * init() will replace it with the properly configured executor.
     */
    private static synchronized void ensureExecutor() {
        if (scheduler != null) return;
        VirtualThreadScheduledExecutor v = new VirtualThreadScheduledExecutor("play");
        scheduler = v;
    }

    /**
     * Initialize the invoker executor. Must be called after configuration is loaded.
     * Shuts down any pre-existing executor first so DEV-mode hot reloads don't leak
     * scheduler threads or stale delayed work across restarts.
     *
     * Synchronized on the same monitor as {@link #stop()} so DEV-mode hot reloads cannot
     * interleave a swap with a concurrent shutdown.
     */
    public static synchronized void init() {
        // Lazy-boot in DEV: the first request creates a bootstrap scheduler via
        // {@link #ensureExecutor()}, then runs the request on a VT in that scheduler.
        // The VT's request handler is what triggers {@code Play.start()} →
        // {@code Invoker.init()} — so calling {@code stop()} → {@code shutdownNow()}
        // here would interrupt the calling thread itself, and the first
        // {@code @OnApplicationStart} job's first blocking I/O would abort with
        // {@link InterruptedException}. Hot reloads route through {@link Play#stop()}
        // first, which clears {@code scheduler} via {@link #stop()}, so by the time
        // {@code Invoker.init()} runs again {@code scheduler} is {@code null} and the
        // normal path is taken. Hence: a non-null scheduler at this entry implies the
        // bootstrap-from-ensureExecutor case — reuse it rather than sawing off the
        // branch the caller is sitting on.
        if (scheduler != null && Thread.currentThread().isVirtual()) {
            Logger.info("Invoker using virtual threads (kept bootstrap executor)");
            return;
        }
        stop();
        VirtualThreadScheduledExecutor v = new VirtualThreadScheduledExecutor("play");
        scheduler = v;
        Logger.info("Invoker using virtual threads");
    }

    /**
     * Shut down the active executor and release its threads. Called from
     * {@link Play#stop()} and from {@link #init()} so that DEV restarts and test
     * teardowns don't accumulate scheduler thread pools or pending delayed work.
     */
    public static synchronized void stop() {
        VirtualThreadScheduledExecutor s = scheduler;
        if (s != null) s.shutdownNow();
        scheduler = null;
    }

    /**
     * Throwable to indicate that the request must be suspended
     */
    public static class Suspend extends PlayException {

        /**
         * Suspend for a timeout (in milliseconds).
         */
        long timeout;

        /**
         * Wait for task execution.
         */
        Future<?> task;

        public Suspend(long timeout) {
            this.timeout = timeout;
        }

        public Suspend(Future<?> task) {
            this.task = task;
        }

        @Override
        public String getErrorTitle() {
            return "Request is suspended";
        }

        @Override
        public String getErrorDescription() {
            if (task != null) {
                return "Wait for " + task;
            }
            return "Retry in " + timeout + " ms.";
        }
    }

    /**
     * Utility for resuming suspended requests when a Future completes. The VT-only
     * dispatch model lets each waiter park on its own virtual thread (cheap), so this
     * collapses to: subscribe to Promise redeem, or submit a VT that blocks on
     * {@code task.get()} and then re-submits the invocation.
     */
    static class WaitForTasksCompletion {

        public static <V> void waitFor(Future<V> task, final Invocation invocation) {
            if (task instanceof Promise) {
                Promise<V> smartFuture = (Promise<V>) task;
                smartFuture.onRedeem(result -> resumeQuietly(invocation));
            } else {
                // Block a virtual thread on the future. Sub-millisecond resume latency;
                // the per-future VT is essentially free under Loom.
                scheduler.submit(() -> {
                    try {
                        task.get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                        // The invocation will inspect task state itself; we just resume it.
                    }
                    resumeQuietly(invocation);
                });
            }
        }

        /**
         * Audit H3: submit a suspended-then-ready invocation back through the scheduler,
         * swallowing {@link RejectedExecutionException} (logged at WARN). Routes through
         * {@link #submitTracked(Runnable)} so suspended-resume invocations are counted
         * by the H2-counter observability fields.
         */
        private static void resumeQuietly(Invocation invocation) {
            try {
                submitTracked(invocation);
            } catch (RejectedExecutionException rex) {
                String ctx;
                try {
                    InvocationContext ic = invocation.getInvocationContext();
                    ctx = ic == null ? "unknown" : ic.toString();
                } catch (Throwable t) {
                    ctx = "unavailable";
                }
                Logger.warn("Cannot resume suspended invocation after scheduler shutdown (%s)", ctx);
            }
        }
    }
}
