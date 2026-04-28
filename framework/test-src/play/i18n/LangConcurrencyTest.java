package play.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Audit M17: Lang.getLocale's cache must tolerate concurrent calls. The previous
 * HashMap implementation could corrupt its bucket array under concurrent writers,
 * losing entries silently and (in pathological cases) looping forever inside
 * HashMap.put. ConcurrentHashMap's computeIfAbsent gives atomic check-and-fill.
 */
public class LangConcurrencyTest {

    @Test
    public void concurrentGetLocaleProducesConsistentResults() throws InterruptedException {
        int threads = 32;
        int callsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        // Mix of cache hits and cache fills. Different locales rotate so writes
        // happen continuously, not just on the first hit.
        String[] locales = {"en", "fr", "ja", "de", "es", "it", "ko", "zh", "pt", "ru"};

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        String loc = locales[i % locales.length];
                        Locale result = Lang.getLocale(loc);
                        if (result == null || !result.getLanguage().equalsIgnoreCase(loc)) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(errors.get()).isZero();
    }
}
