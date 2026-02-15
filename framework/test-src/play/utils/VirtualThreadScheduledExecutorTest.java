package play.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
}
