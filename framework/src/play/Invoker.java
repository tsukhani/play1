package play;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import play.utils.ExecutorFacade;
import play.utils.PThreadFactory;
import play.utils.VirtualThreadConfig;
import play.utils.VirtualThreadScheduledExecutor;

/**
 * Run some code in a Play! context
 */
public class Invoker {

    /**
     * Unified scheduling facade. All internal call sites route dispatches through this;
     * the {@link #executor} / {@link #virtualExecutor} / {@link #usingVirtualThreads}
     * fields below are deprecated mirrors kept for binary compatibility with third-party
     * plugins.
     */
    public static final ExecutorFacade scheduler = new ExecutorFacade();

    /**
     * @deprecated Use {@link #scheduler} (or {@link ExecutorFacade#platformExecutor()})
     *     for status reporting. Direct mutation of this field is unsupported and will
     *     desynchronize from the facade. Will be removed in a future release.
     */
    @Deprecated
    public static volatile ScheduledThreadPoolExecutor executor = null;

    /**
     * @deprecated Use {@link #scheduler} (or {@link ExecutorFacade#virtualExecutor()}).
     */
    @Deprecated
    public static volatile VirtualThreadScheduledExecutor virtualExecutor = null;

    /**
     * @deprecated Use {@link ExecutorFacade#isUsingVirtualThreads()} on {@link #scheduler}.
     */
    @Deprecated
    public static volatile boolean usingVirtualThreads = false;

    /**
     * Run the code in a new thread took from a thread pool.
     *
     * @param invocation
     *            The code to run
     * @return The future object, to know when the task is completed
     */
    public static Future<?> invoke(Invocation invocation) {
        if (!scheduler.isUsingVirtualThreads()) {
            ensureExecutor();
            Monitor monitor = MonitorFactory.getMonitor("Invoker queue size", "elmts.");
            monitor.add(scheduler.platformExecutor().getQueue().size());
        }
        invocation.waitInQueue = MonitorFactory.start("Waiting for execution");
        return scheduler.submit(invocation);
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
        if (!scheduler.isUsingVirtualThreads()) {
            ensureExecutor();
            Monitor monitor = MonitorFactory.getMonitor("Invocation queue", "elmts.");
            monitor.add(scheduler.platformExecutor().getQueue().size());
        }
        return scheduler.schedule(invocation, millis, TimeUnit.MILLISECONDS);
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
        if (scheduler.isUsingVirtualThreads()) {
            // Virtual threads cannot be enumerated with Thread.enumerate().
            // Each virtual thread sets its context classloader at the start of an invocation
            // (in Invocation.init() and Invocation.before()), so stale classloaders are
            // naturally replaced on the next invocation.
            return;
        }
        ScheduledThreadPoolExecutor pool = scheduler.platformExecutor();
        if (pool == null) return;
        Thread[] executorThreads = new Thread[pool.getPoolSize()];
        Thread.enumerate(executorThreads);
        for (Thread thread : executorThreads) {
            if (thread != null && thread.getContextClassLoader() instanceof ApplicationClassloader)
                thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
        }
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
                suspend(e);
                after();
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
        if (scheduler.platformExecutor() == null && !scheduler.isUsingVirtualThreads()) {
            ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1, new PThreadFactory("play"), new ThreadPoolExecutor.AbortPolicy());
            scheduler.usePlatform(p);
            executor = p;
        }
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
        stop();
        if (VirtualThreadConfig.isInvokerEnabled()) {
            VirtualThreadScheduledExecutor v = new VirtualThreadScheduledExecutor("play");
            scheduler.useVirtual(v);
            virtualExecutor = v;
            executor = null;
            usingVirtualThreads = true;
            Logger.info("Invoker using virtual threads");
        } else {
            int core = Integer.parseInt(Play.configuration.getProperty("play.pool",
                    Play.mode == Mode.DEV ? "1" : String.valueOf(Runtime.getRuntime().availableProcessors() + 1)));
            ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(core, new PThreadFactory("play"), new ThreadPoolExecutor.AbortPolicy());
            scheduler.usePlatform(p);
            executor = p;
            virtualExecutor = null;
            usingVirtualThreads = false;
        }
    }

    /**
     * Shut down whichever executor is currently active and release its threads.
     * Called from {@link Play#stop()} and from {@link #init()} so that DEV restarts and
     * test teardowns don't accumulate scheduler thread pools or pending delayed tasks.
     */
    public static synchronized void stop() {
        scheduler.shutdownNow();
        executor = null;
        virtualExecutor = null;
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
     * Utility that track tasks completion in order to resume suspended requests.
     */
    static class WaitForTasksCompletion extends Thread {

        // volatile: this is the classic double-checked-lock instance field. The synchronized
        // block in waitFor() guarantees publication for a fresh instance, but a *read* of the
        // field from another thread (a future caller checking `instance != null` outside the
        // lock) needs volatile to see the published Thread reference.
        static volatile WaitForTasksCompletion instance;
        final Map<Future<?>, Invocation> queue = new ConcurrentHashMap<>();

        public WaitForTasksCompletion() {
            setName("WaitForTasksCompletion");
            setDaemon(true);
        }

        public static <V> void waitFor(Future<V> task, final Invocation invocation) {
            if (task instanceof Promise) {
                Promise<V> smartFuture = (Promise<V>) task;
                smartFuture.onRedeem(result -> scheduler.submit(invocation));
            } else if (scheduler.isUsingVirtualThreads()) {
                // Block a cheap virtual thread on the future instead of polling. Avoids the
                // 50 ms scan loop and gives sub-millisecond resume latency. Virtual threads
                // make the per-future thread allocation negligible.
                scheduler.submit(() -> {
                    try {
                        task.get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                        // The invocation will inspect task state itself; we just resume it.
                    }
                    scheduler.submit(invocation);
                });
            } else {
                synchronized (WaitForTasksCompletion.class) {
                    if (instance == null) {
                        instance = new WaitForTasksCompletion();
                        Logger.warn("Start WaitForTasksCompletion");
                        instance.start();
                    }
                    instance.queue.put(task, invocation);
                }
            }
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!queue.isEmpty()) {
                        for (Future<?> task : new HashSet<>(queue.keySet())) {
                            if (task.isDone()) {
                                Invocation invocation = queue.remove(task);
                                scheduler.submit(invocation);
                            }
                        }
                    }
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    // Restore interrupt and exit; the loop's interrupt-check will not re-enter.
                    Thread.currentThread().interrupt();
                    Logger.info("WaitForTasksCompletion interrupted, exiting");
                    return;
                }
            }
        }
    }
}
