package play;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link Invoker#awaitQuiescence(long)} — the lock-free in-flight drain primitive the
 * standalone shutdown hook uses to let active request invocations finish before plugin shutdown
 * (PF-119 follow-up). The counter is a shared {@code public static} {@link java.util.concurrent.atomic.AtomicLong},
 * so we drive it directly rather than standing up a real request pipeline.
 */
public class InvokerQuiescenceTest {

    @AfterEach
    void resetCounter() {
        // No real requests run in this unit JVM, so 0 is the correct baseline to restore.
        Invoker.inflightInvocations.set(0);
    }

    @Test
    void returnsImmediatelyWhenAlreadyQuiescent() {
        Invoker.inflightInvocations.set(0);
        assertThat(Invoker.awaitQuiescence(5000)).isTrue();
    }

    @Test
    void returnsTrueOnceInFlightCountReachesZero() throws Exception {
        Invoker.inflightInvocations.set(3);

        Thread drainer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Invoker.inflightInvocations.set(0);
        });
        drainer.start();

        assertThat(Invoker.awaitQuiescence(5000)).isTrue();
        drainer.join();
    }

    @Test
    void timesOutWhenInFlightWorkRemains() {
        Invoker.inflightInvocations.set(1);

        long start = System.nanoTime();
        boolean drained = Invoker.awaitQuiescence(120);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(drained).isFalse();
        // It must actually wait out (most of) the budget, not return early.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
    }

    @Test
    void nonPositiveTimeoutPollsOnceWithoutWaiting() {
        Invoker.inflightInvocations.set(0);
        assertThat(Invoker.awaitQuiescence(0)).isTrue();

        Invoker.inflightInvocations.set(2);
        assertThat(Invoker.awaitQuiescence(0)).isFalse();
    }
}
