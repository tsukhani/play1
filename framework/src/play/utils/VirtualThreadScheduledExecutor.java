package play.utils;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import play.Logger;

/**
 * A scheduled executor that delegates scheduling to a small platform-thread
 * {@link ScheduledThreadPoolExecutor} and dispatches actual work to virtual threads.
 *
 * <p>Scheduler threads are only occupied for the microsecond it takes to hand off to the
 * virtual executor — they never block waiting for the dispatched work to complete.
 * Without this property a handful of long-running scheduled tasks could exhaust the
 * 2-thread scheduler pool and starve unrelated timers, suspended-request resumes, and
 * cron jobs.</p>
 *
 * <p>The {@link ScheduledFuture} returned by {@code schedule()} still tracks completion
 * of the dispatched work via a {@link CompletableFuture} wrapper, so {@code future.get()}
 * blocks until the virtual thread has finished — the wait happens on the caller's thread,
 * not on a scarce scheduler thread.</p>
 *
 * <p>Fixed-delay semantics for {@link #scheduleWithFixedDelay} are preserved via a
 * self-rescheduling chain: each virtual-thread completion schedules the next run
 * itself, so "next run starts after previous completes + delay" is honored without
 * pinning a scheduler thread for the task duration.</p>
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
     * Schedule a callable for delayed execution. The scheduler thread fires the dispatch
     * to the virtual executor and returns immediately — it does NOT block on the work.
     * The returned future still completes when the virtual thread finishes (so callers
     * may {@code .get()} it), but the wait happens on the caller's thread.
     *
     * <p>{@code cancel(true)} on the returned future propagates the interrupt to the
     * in-flight virtual thread (if one has been submitted) so callers can actually stop
     * a long-running task — not just be told it was cancelled while it keeps producing
     * side effects. The cancel-after-dispatch race is handled by re-checking the wrapper's
     * cancelled state right after publishing the inner future.</p>
     */
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        CompletableFuture<V> done = new CompletableFuture<>();
        AtomicReference<Future<?>> innerRef = new AtomicReference<>();
        ScheduledFuture<?> dispatch = scheduler.schedule(() -> {
            try {
                Future<?> inner = virtualExecutor.submit(() -> {
                    try {
                        done.complete(callable.call());
                    } catch (Throwable t) {
                        done.completeExceptionally(t);
                    }
                });
                innerRef.set(inner);
                // Close the race window: a cancel(true) that fired between submit() and
                // set() would otherwise leave the virtual thread running. Replay the
                // interrupt now if the wrapper was cancelled in that window.
                if (done.isCancelled()) {
                    inner.cancel(true);
                }
            } catch (RejectedExecutionException ree) {
                done.completeExceptionally(ree);
            }
        }, delay, unit);
        return new CompletionTrackingFuture<>(dispatch, done, innerRef);
    }

    /**
     * Schedule a runnable for delayed execution. As with the callable variant, the
     * scheduler thread dispatches and returns immediately; {@code .get()} on the returned
     * future blocks the caller until the virtual thread finishes.
     */
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(() -> {
            command.run();
            return null;
        }, delay, unit);
    }

    /**
     * Schedule a task with fixed delay. Each execution runs on a virtual thread and
     * self-reschedules the next run on completion, so the scheduler thread is never held
     * for the task duration. Fixed-delay semantics (next start = previous completion + delay)
     * are preserved.
     *
     * <p>Failure semantics match {@link ScheduledThreadPoolExecutor#scheduleWithFixedDelay}:
     * if a run throws {@link Throwable}, subsequent executions are *suppressed* and the
     * returned future enters its terminal state with {@link Future#get()} reporting the
     * cause via {@link ExecutionException}. Without this, a poisoned periodic job would
     * loop forever, spamming logs and (worse) holding resources its caller assumed had
     * been released when the future "failed".</p>
     *
     * <p>{@code cancel(true)} propagates interruption to the currently running virtual
     * thread (if any) in addition to cancelling the next pending scheduler dispatch.</p>
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        SelfReschedulingFuture handle = new SelfReschedulingFuture();
        AtomicReference<Runnable> taskRef = new AtomicReference<>();
        taskRef.set(() -> {
            // H1 + M14: short-circuit on terminal handle state OR scheduler shutdown.
            // Use isDone() not isCancelled() — completeExceptionally sets failure!=null
            // but isCancelled() returns false in that case, so a fast dispatch queued
            // just before abnormal termination could slip past and re-run a job the
            // previous iteration already failed (audit M14). Split the shutdown branch
            // so a shutdown between runs ALSO drives the handle to terminal state:
            // STPE's default policy executes already-delayed tasks after shutdown();
            // without the explicit handle.cancel(false), the dispatch would observe
            // isShutdown(), bail, and never count down the terminated latch — leaving
            // callers blocked on handle.get() forever (audit H1).
            if (handle.isDone()) return;
            if (scheduler.isShutdown()) {
                handle.cancel(false);
                return;
            }
            Future<?> inner = virtualExecutor.submit(() -> {
                try {
                    command.run();
                } catch (Throwable t) {
                    // Match ScheduledThreadPoolExecutor: a throwing run terminates the
                    // periodic task. Record the cause and stop rescheduling.
                    Logger.error(t, "Scheduled task threw; suppressing further executions");
                    handle.completeExceptionally(t);
                    return;
                }
                // Reschedule, or transition the handle to terminal state when the
                // scheduler is shut down between runs. Without the explicit cancel
                // calls below, a periodic future could remain non-terminal forever:
                // isDone() would return false and get() would block, diverging from
                // ScheduledThreadPoolExecutor's "shutdown cancels periodic tasks"
                // contract.
                if (handle.isDone()) {
                    return;
                }
                if (scheduler.isShutdown()) {
                    handle.cancel(false);
                    return;
                }
                try {
                    handle.setNext(scheduler.schedule(taskRef.get(), delay, unit));
                } catch (RejectedExecutionException ree) {
                    handle.cancel(false);
                }
            });
            handle.setActiveInner(inner);
        });
        handle.setNext(scheduler.schedule(taskRef.get(), initialDelay, unit));
        return handle;
    }

    /**
     * Initiate an orderly shutdown: the scheduler stops accepting new tasks but
     * lets in-flight work complete. Pair with {@link #awaitTermination} for
     * graceful drain semantics; call {@link #shutdownNow} only if the await
     * times out.
     */
    public void shutdown() {
        scheduler.shutdown();
        virtualExecutor.shutdown();
    }

    /**
     * Block up to {@code timeout} for both the scheduler and the virtual-thread
     * executor to finish their in-flight work after a {@link #shutdown} call.
     * Returns true if both terminated within the budget.
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanosBudget = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanosBudget;
        boolean schedulerDone = scheduler.awaitTermination(nanosBudget, TimeUnit.NANOSECONDS);
        long remaining = Math.max(0L, deadline - System.nanoTime());
        boolean executorDone = virtualExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        return schedulerDone && executorDone;
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
     * Orderly shutdown of the scheduler. Already-queued dispatches are still allowed to
     * fire (STPE's default {@code executeExistingDelayedTasksAfterShutdownPolicy}); each
     * such firing observes {@link #scheduler}{@code .isShutdown()} and drives its
     * fixed-delay handle to terminal state via {@code handle.cancel(false)} (see H1).
     * The virtual executor is left untouched so in-flight runs can complete.
     */
    public void shutdownScheduler() {
        scheduler.shutdown();
    }

    /**
     * Returns the underlying scheduler's queue, for compatibility with code that
     * inspects scheduled tasks (e.g., JobsPlugin waiting jobs display).
     */
    public java.util.concurrent.BlockingQueue<Runnable> getSchedulerQueue() {
        return scheduler.getQueue();
    }

    /**
     * A {@link ScheduledFuture} whose {@code getDelay()} reflects the scheduler's pending
     * dispatch time and whose {@code get()} blocks the caller (not the scheduler) until
     * the dispatched virtual-thread work has finished.
     *
     * <p>{@code cancel(true)} interrupts the running virtual-thread work via
     * {@code innerRef} so a long-running task is actually stopped, not just reported as
     * cancelled while its side-effects continue.</p>
     */
    private static final class CompletionTrackingFuture<V> implements ScheduledFuture<V> {
        private final ScheduledFuture<?> dispatch;
        private final CompletableFuture<V> result;
        private final AtomicReference<Future<?>> innerRef;

        CompletionTrackingFuture(ScheduledFuture<?> dispatch, CompletableFuture<V> result,
                                 AtomicReference<Future<?>> innerRef) {
            this.dispatch = dispatch;
            this.result = result;
            this.innerRef = innerRef;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return dispatch.getDelay(unit);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean dispatchCancelled = dispatch.cancel(mayInterruptIfRunning);
            boolean resultCancelled = result.cancel(mayInterruptIfRunning);
            // Propagate interruption to the in-flight virtual-thread work (if dispatched).
            // The schedule() method also re-checks result.isCancelled() right after publishing
            // the inner future so a cancel that races with the dispatch isn't lost.
            Future<?> inner = innerRef.get();
            if (inner != null) {
                inner.cancel(mayInterruptIfRunning);
            }
            return dispatchCancelled || resultCancelled;
        }

        @Override
        public boolean isCancelled() {
            return result.isCancelled() || dispatch.isCancelled();
        }

        @Override
        public boolean isDone() {
            return result.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }

    /**
     * A ScheduledFuture wrapper that supports cancellation across self-rescheduling chains
     * and matches {@link ScheduledThreadPoolExecutor}'s periodic-task semantics:
     * <ul>
     *   <li>{@code cancel(true)} prevents future runs, cancels the currently-pending
     *       scheduler entry, and interrupts the in-flight virtual-thread work via
     *       {@link #setActiveInner}.</li>
     *   <li>If a run throws, {@link #completeExceptionally} marks the future done with
     *       the exception so {@code get()} throws {@link ExecutionException}, mirroring
     *       STPE's "subsequent executions are suppressed" contract.</li>
     * </ul>
     */
    private static final class SelfReschedulingFuture implements ScheduledFuture<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<ScheduledFuture<?>> next = new AtomicReference<>();
        // Tracks the currently in-flight virtual-thread submission. cancel(true) propagates
        // through this so a long-running periodic task is actually interrupted.
        private final AtomicReference<Future<?>> activeInner = new AtomicReference<>();
        // Stored cause when a run throws. get() rethrows this as ExecutionException to
        // match the abnormal-termination reporting STPE provides via its returned future.
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        // Released when the future enters its terminal state — cancellation OR a throwing
        // run. A periodic task has no natural completion otherwise, matching STPE's
        // "the task can only terminate via cancellation or termination of the executor"
        // (extended here to include the abnormal-termination case).
        private final CountDownLatch terminated = new CountDownLatch(1);

        /**
         * Publish the next scheduled run. If cancellation raced ahead of the publication,
         * cancel the just-published future too, so a cancel() call cannot leak a still-firing
         * task that was scheduled after cancel() read the previous {@code next}.
         */
        void setNext(ScheduledFuture<?> sf) {
            next.set(sf);
            if (cancelled.get()) {
                sf.cancel(false);
                next.compareAndSet(sf, null);
            }
        }

        /**
         * Publish the in-flight virtual-thread submission so {@link #cancel} can interrupt it.
         * If a cancel(true) raced ahead, replay the interrupt on the just-published inner.
         */
        void setActiveInner(Future<?> inner) {
            activeInner.set(inner);
            if (cancelled.get()) {
                inner.cancel(true);
            }
        }

        /**
         * Mark the periodic task as terminated due to an abnormal run. After this, no
         * further executions will be scheduled and {@link #get()} throws {@link ExecutionException}.
         */
        void completeExceptionally(Throwable t) {
            if (failure.compareAndSet(null, t)) {
                // Stop future runs from rescheduling themselves: cancellation makes the
                // self-rescheduling task short-circuit before submitting the next.
                cancelled.set(true);
                ScheduledFuture<?> sf = next.getAndSet(null);
                if (sf != null) sf.cancel(false);
                terminated.countDown();
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            ScheduledFuture<?> sf = next.get();
            return sf != null ? sf.getDelay(unit) : 0L;
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean firstCancel = cancelled.compareAndSet(false, true);
            // getAndSet ensures a concurrent setNext() observing cancelled=true will replay
            // the cancellation on the future it just installed (see setNext above).
            ScheduledFuture<?> sf = next.getAndSet(null);
            boolean cancelledInner = sf == null || sf.cancel(mayInterruptIfRunning);
            // Propagate interruption to the running virtual-thread work, if any. Same race
            // protection on the publication side is in setActiveInner().
            Future<?> inner = activeInner.getAndSet(null);
            if (inner != null) {
                inner.cancel(mayInterruptIfRunning);
            }
            if (firstCancel) {
                terminated.countDown();
            }
            return cancelledInner;
        }

        @Override
        public boolean isCancelled() {
            // Distinguish user cancel from abnormal termination: only return true for the
            // former so isCancelled()/get() consumers see STPE-equivalent reporting.
            return cancelled.get() && failure.get() == null;
        }

        @Override
        public boolean isDone() {
            // STPE periodic-future contract: a periodic task is "done" only on terminal
            // state — cancellation or abnormal termination. The scheduler-side dispatch
            // future would complete as soon as it hands off to the virtual executor, so
            // delegating to it would falsely report done while the periodic body is still
            // running. Track only the terminal state here.
            return cancelled.get() || failure.get() != null;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            terminated.await();
            Throwable t = failure.get();
            if (t != null) throw new ExecutionException(t);
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (!terminated.await(timeout, unit)) {
                throw new TimeoutException();
            }
            Throwable t = failure.get();
            if (t != null) throw new ExecutionException(t);
            return null;
        }
    }
}
