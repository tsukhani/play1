package play.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
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
 * <p>Exactly one of {@link #platformExecutor()} / {@link #virtualExecutor()} is
 * non-null at any moment, mirroring the previous {@code executor} / {@code virtualExecutor}
 * field pair on Invoker and JobsPlugin. Accessors are exposed for status reporting; the
 * normal task path uses {@link #submit}, {@link #schedule}, {@link #scheduleWithFixedDelay}.</p>
 */
public final class ExecutorFacade {

    private volatile ScheduledThreadPoolExecutor platform;
    private volatile VirtualThreadScheduledExecutor virtual;
    private volatile boolean usingVirtualThreads;

    /**
     * Install a virtual-thread-backed scheduler. Replaces any previous executor.
     * Caller is responsible for shutting down the old one if needed (typically via
     * {@link #shutdownNow()} before this call).
     */
    public synchronized void useVirtual(VirtualThreadScheduledExecutor virtual) {
        this.virtual = virtual;
        this.platform = null;
        this.usingVirtualThreads = true;
    }

    /**
     * Install a platform-thread-backed scheduler. Replaces any previous executor.
     */
    public synchronized void usePlatform(ScheduledThreadPoolExecutor platform) {
        this.platform = platform;
        this.virtual = null;
        this.usingVirtualThreads = false;
    }

    public boolean isUsingVirtualThreads() {
        return usingVirtualThreads;
    }

    /** May be null if the facade is using virtual threads or has not been initialized. */
    public ScheduledThreadPoolExecutor platformExecutor() {
        return platform;
    }

    /** May be null if the facade is using platform threads or has not been initialized. */
    public VirtualThreadScheduledExecutor virtualExecutor() {
        return virtual;
    }

    public Future<?> submit(Runnable task) {
        return usingVirtualThreads ? virtual.submit(task) : platform.submit(task);
    }

    public <V> Future<V> submit(Callable<V> task) {
        return usingVirtualThreads ? virtual.submit(task) : platform.submit(task);
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return usingVirtualThreads ? virtual.schedule(command, delay, unit) : platform.schedule(command, delay, unit);
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return usingVirtualThreads ? virtual.schedule(callable, delay, unit) : platform.schedule(callable, delay, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return usingVirtualThreads
                ? virtual.scheduleWithFixedDelay(command, initialDelay, delay, unit)
                : platform.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    /**
     * Shut down whichever executor is currently active and clear the reference.
     */
    public synchronized void shutdownNow() {
        if (platform != null) {
            platform.shutdownNow();
            platform = null;
        }
        if (virtual != null) {
            virtual.shutdownNow();
            virtual = null;
        }
    }
}
