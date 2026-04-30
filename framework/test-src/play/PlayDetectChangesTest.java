package play;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class PlayDetectChangesTest {

    @Test
    void detectChangesIsLockFreeInProdMode() throws Exception {
        // PF-61: detectChanges in PROD must not acquire the Play.class monitor.
        // Pre-fix it was a static synchronized method, so the PROD short-circuit
        // ran inside the lock — which deadlocked at shutdown when Play.stop held
        // Play.class while waiting on @Every-dispatched VTs that themselves were
        // blocked at detectChanges' synchronized entry. Hold the monitor here on
        // a separate thread; the call must return immediately.
        Play.Mode previousMode = Play.mode;
        Play.mode = Play.Mode.PROD;
        try {
            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            Thread lockHolder = Thread.ofPlatform().start(() -> {
                synchronized (Play.class) {
                    lockHeld.countDown();
                    try {
                        releaseLock.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            try {
                assertThat(lockHeld.await(2, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                Play.detectChanges();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                // If detectChanges still acquired the monitor, it would block until
                // releaseLock fires below (5s budget). Returning fast proves the
                // unsynchronized PROD fast-path is active.
                assertThat(elapsedMs).isLessThan(500L);
            } finally {
                releaseLock.countDown();
                lockHolder.join(5_000);
            }
        } finally {
            Play.mode = previousMode;
        }
    }
}
