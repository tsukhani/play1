package play.utils;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A scheduled executor that delegates scheduling to a small platform-thread
 * {@link ScheduledThreadPoolExecutor} and dispatches actual work to virtual threads.
 *
 * <p>This preserves the {@code schedule()} and {@code scheduleWithFixedDelay()} APIs
 * required by the Invoker and JobsPlugin while running actual task work on virtual threads.</p>
 */
public class VirtualThreadScheduledExecutor {

    private final ScheduledThreadPoolExecutor scheduler;
    private final ExecutorService virtualExecutor;

    public VirtualThreadScheduledExecutor(String name) {
        this.scheduler = new ScheduledThreadPoolExecutor(2, new PThreadFactory(name + "-scheduler"));
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.virtualExecutor = Executors.newThreadPerTaskExecutor(new VirtualThreadFactory(name));
    }

    /**
     * Submit a task for immediate execution on a virtual thread.
     */
    public Future<?> submit(Runnable task) {
        return virtualExecutor.submit(task);
    }

    /**
     * Submit a callable for immediate execution on a virtual thread.
     */
    public <T> Future<T> submit(Callable<T> task) {
        return virtualExecutor.submit(task);
    }

    /**
     * Schedule a callable for delayed execution. The delay is managed by a platform-thread
     * scheduler, but the actual work runs on a virtual thread.
     */
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return scheduler.schedule(() -> virtualExecutor.submit(callable).get(), delay, unit);
    }

    /**
     * Schedule a runnable for delayed execution. The delay is managed by a platform-thread
     * scheduler, but the actual work runs on a virtual thread.
     */
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return scheduler.schedule(() -> virtualExecutor.submit(command).get(), delay, unit);
    }

    /**
     * Schedule a task with fixed delay. Each execution runs on a virtual thread.
     * The fixed-delay semantics (next execution starts after the previous one completes + delay)
     * are preserved by the platform-thread scheduler.
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                virtualExecutor.submit(command).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, initialDelay, delay, unit);
    }

    /**
     * Shut down both the scheduler and the virtual thread executor.
     *
     * @return list of tasks that never commenced execution (from the scheduler)
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> pending = scheduler.shutdownNow();
        virtualExecutor.shutdownNow();
        return pending;
    }

    /**
     * Returns the underlying scheduler's queue, for compatibility with code that
     * inspects scheduled tasks (e.g., JobsPlugin waiting jobs display).
     */
    public java.util.concurrent.BlockingQueue<Runnable> getSchedulerQueue() {
        return scheduler.getQueue();
    }
}
