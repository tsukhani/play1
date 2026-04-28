package play.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class VirtualThreadScheduledExecutorTest {

    private VirtualThreadScheduledExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new VirtualThreadScheduledExecutor("test");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void submitRunnableExecutesOnVirtualThread() throws Exception {
        AtomicBoolean isVirtual = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void submitCallableExecutesOnVirtualThread() throws Exception {
        Future<Boolean> future = executor.submit(() -> Thread.currentThread().isVirtual());
        assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void scheduleCallableExecutesAfterDelay() throws Exception {
        AtomicLong executionTime = new AtomicLong();
        AtomicBoolean isVirtual = new AtomicBoolean(false);

        long start = System.nanoTime();
        Future<Void> future = executor.schedule(() -> {
            executionTime.set(System.nanoTime());
            isVirtual.set(Thread.currentThread().isVirtual());
            return null;
        }, 200, TimeUnit.MILLISECONDS);

        future.get(5, TimeUnit.SECONDS);

        long elapsedMs = (executionTime.get() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150); // allow some slack
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void scheduleRunnableExecutesAfterDelay() throws Exception {
        AtomicBoolean isVirtual = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        long start = System.nanoTime();
        executor.schedule(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        }, 200, TimeUnit.MILLISECONDS);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150);
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void scheduleWithFixedDelayPreservesFixedDelaySemantics() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<Boolean> allVirtual = new AtomicReference<>(true);
        CountDownLatch latch = new CountDownLatch(3);

        executor.scheduleWithFixedDelay(() -> {
            if (!Thread.currentThread().isVirtual()) {
                allVirtual.set(false);
            }
            count.incrementAndGet();
            latch.countDown();
        }, 0, 100, TimeUnit.MILLISECONDS);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isGreaterThanOrEqualTo(3);
        assertThat(allVirtual.get()).isTrue();
    }

    @Test
    void scheduleWithFixedDelaySuppressesFurtherExecutionsOnThrow() throws Exception {
        // Matches ScheduledThreadPoolExecutor#scheduleWithFixedDelay: a throwing run
        // terminates the periodic task. get() then reports the cause via ExecutionException.
        AtomicInteger runs = new AtomicInteger(0);
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        }, 0, 50, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);

        // Allow time for any (incorrect) reschedules to fire — there should be exactly one run.
        int runsAtFailure = runs.get();
        Thread.sleep(300);
        assertThat(runs.get()).isEqualTo(runsAtFailure);
        assertThat(future.isDone()).isTrue();
        assertThat(future.isCancelled()).isFalse(); // abnormal termination, not user cancel
    }

    @Test
    void cancelPropagatesInterruptToInFlightVirtualThread() throws Exception {
        // After the scheduler hands work to the virtual executor, cancel(true) must
        // interrupt the running virtual thread — not just mark the wrapper cancelled
        // while side-effects continue.
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        AtomicBoolean ranToCompletion = new AtomicBoolean(false);

        ScheduledFuture<?> future = executor.schedule(() -> {
            started.countDown();
            try {
                Thread.sleep(5_000); // long enough that the test always cancels first
            } catch (InterruptedException ie) {
                wasInterrupted.set(true);
                Thread.currentThread().interrupt();
                return;
            }
            ranToCompletion.set(true);
        }, 0, TimeUnit.MILLISECONDS);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        future.cancel(true);

        // Give the interrupt time to land. Without the fix, the sleep would never be
        // interrupted and ranToCompletion would eventually flip true.
        Thread.sleep(200);
        assertThat(wasInterrupted.get()).isTrue();
        assertThat(ranToCompletion.get()).isFalse();
    }

    @Test
    void scheduleWithFixedDelayCancelInterruptsActiveRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        AtomicInteger runs = new AtomicInteger(0);

        ScheduledFuture<?> handle = executor.scheduleWithFixedDelay(() -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ie) {
                wasInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        handle.cancel(true);

        Thread.sleep(200);
        assertThat(wasInterrupted.get()).isTrue();
        assertThat(handle.isCancelled()).isTrue();
        // No further runs should have been scheduled after cancellation.
        int runsAtCancel = runs.get();
        Thread.sleep(300);
        assertThat(runs.get()).isEqualTo(runsAtCancel);
    }

    @Test
    void shutdownNowStopsExecution() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);

        executor.submit(() -> {
            started.countDown();
            try {
                blocked.await(); // block forever
            } catch (InterruptedException e) {
                // expected on shutdown
            }
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();
        // Just verify shutdownNow returns without hanging
    }

    @Test
    void scheduleWithFixedDelayShutdownBetweenRunsDrainsHandle() throws Exception {
        // H1: STPE's default "execute existing delayed tasks after shutdown" policy
        // means a fixed-delay dispatch can still fire after scheduler.shutdown(). The
        // dispatch's early-return on isShutdown() must drive the handle to terminal
        // state, not silently return — otherwise terminated never counts down and
        // get() blocks forever.
        //
        // Reproduction: let the task self-reschedule (so the next dispatch is queued
        // in the scheduler), then call shutdownScheduler(). The queued dispatch will
        // fire per STPE's default executeExistingDelayedTasksAfterShutdownPolicy and
        // hit the H1 early-return — pre-fix the handle is never terminal.
        VirtualThreadScheduledExecutor local = new VirtualThreadScheduledExecutor("h1-test");
        try {
            CountDownLatch firstRun = new CountDownLatch(1);
            ScheduledFuture<?> handle = local.scheduleWithFixedDelay(
                firstRun::countDown,
                0, 500, TimeUnit.MILLISECONDS);

            // Wait for the first run to complete.
            assertThat(firstRun.await(2, TimeUnit.SECONDS)).isTrue();
            // Give the post-run code time to enqueue the next dispatch (setNext()).
            // 500ms repeat delay leaves a wide window before the dispatch fires.
            Thread.sleep(50);

            // Orderly shutdown — the queued dispatch remains in the scheduler and will
            // fire when its delay elapses. The dispatch's early-return on isShutdown()
            // is what we're exercising.
            local.shutdownScheduler();

            // Within ~2 seconds the queued dispatch should fire, observe isShutdown(),
            // and call handle.cancel(false). Pre-fix: handle.isDone() stays false.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!handle.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(handle.isDone()).isTrue();
            assertThat(handle.isCancelled()).isTrue();
        } finally {
            local.shutdownNow();
        }
    }

    @Test
    void scheduleWithFixedDelayIsNotDoneDuringActiveRun() throws Exception {
        // Periodic-future contract (mirrors ScheduledThreadPoolExecutor): isDone() must
        // remain false while the periodic body is executing. Previously this delegated
        // to the scheduler dispatch future, which completes the moment it hands off to
        // the virtual executor — making isDone() falsely report true mid-run.
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, 0, 1, TimeUnit.SECONDS);

        try {
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(future.isDone()).isFalse();
        } finally {
            release.countDown();
            future.cancel(true);
        }

        // After cancellation, isDone must eventually become true.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(future.isDone()).isTrue();
    }
}
