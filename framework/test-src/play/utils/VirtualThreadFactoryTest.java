package play.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class VirtualThreadFactoryTest {

    @Test
    void createdThreadsAreVirtual() {
        VirtualThreadFactory factory = new VirtualThreadFactory("test");
        Thread thread = factory.newThread(() -> {});
        assertThat(thread.isVirtual()).isTrue();
    }

    @Test
    void threadNamesMatchExpectedPattern() {
        VirtualThreadFactory factory = new VirtualThreadFactory("play");
        Thread t1 = factory.newThread(() -> {});
        Thread t2 = factory.newThread(() -> {});
        assertThat(t1.getName()).isEqualTo("play-vthread-1");
        assertThat(t2.getName()).isEqualTo("play-vthread-2");
    }

    @Test
    void counterIncrementsAcrossThreads() {
        VirtualThreadFactory factory = new VirtualThreadFactory("jobs");
        for (int i = 1; i <= 5; i++) {
            Thread t = factory.newThread(() -> {});
            assertThat(t.getName()).isEqualTo("jobs-vthread-" + i);
        }
    }

    @Test
    void differentPrefixesWorkIndependently() {
        VirtualThreadFactory playFactory = new VirtualThreadFactory("play");
        VirtualThreadFactory jobsFactory = new VirtualThreadFactory("jobs");
        Thread pt = playFactory.newThread(() -> {});
        Thread jt = jobsFactory.newThread(() -> {});
        assertThat(pt.getName()).startsWith("play-vthread-");
        assertThat(jt.getName()).startsWith("jobs-vthread-");
    }

    @Test
    void uncaughtExceptionHandlerIsInstalled() {
        // C3: every VT must carry a Play-aware UEH so unhandled exceptions land in
        // Logger.error rather than stderr. Verify the factory installs a non-default
        // handler — the JDK default would route through ThreadGroup.uncaughtException,
        // not the per-thread handler.
        VirtualThreadFactory factory = new VirtualThreadFactory("ueh-check");
        Thread t = factory.newThread(() -> {});
        Thread.UncaughtExceptionHandler ueh = t.getUncaughtExceptionHandler();
        assertThat(ueh).isNotNull();
        // The UEH should be the factory's handler, not the thread itself acting as fallback
        // (Thread.getUncaughtExceptionHandler() returns the thread group when no per-thread
        // handler was set; our handler is a dedicated lambda, so the returned reference
        // must NOT be a ThreadGroup instance).
        assertThat(ueh).isNotInstanceOf(ThreadGroup.class);
    }

    @Test
    void uncaughtExceptionHandlerFiresOnThrow() throws Exception {
        // End-to-end check: a Runnable that throws inside a VT created by this factory
        // must trigger the installed UEH. We chain through the factory's handler by
        // wrapping the thread's existing UEH so we can observe the call without mocking
        // the static Logger.
        VirtualThreadFactory factory = new VirtualThreadFactory("ueh-fire");
        Thread t = factory.newThread(() -> {
            throw new RuntimeException("expected-test-failure");
        });

        CountDownLatch caught = new CountDownLatch(1);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        Thread.UncaughtExceptionHandler factoryUeh = t.getUncaughtExceptionHandler();
        t.setUncaughtExceptionHandler((thread, throwable) -> {
            seen.set(throwable);
            // Still delegate to the factory's handler so its Logger.error path is exercised.
            factoryUeh.uncaughtException(thread, throwable);
            caught.countDown();
        });

        t.start();
        assertThat(caught.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isInstanceOf(RuntimeException.class);
        assertThat(seen.get().getMessage()).isEqualTo("expected-test-failure");
    }
}
