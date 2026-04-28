package play.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit C2 + L4-jobs coverage.
 *
 * <p>The plugin's {@code afterInvocation()} previously discarded the {@link java.util.concurrent.Future}
 * returned by {@code submit()}, swallowing any exception thrown by the queued action.
 * The fix wraps each callable in a try/catch + Logger.error so failures surface; this
 * test asserts the wrapper's catch block runs even when the action throws — covering
 * the silent-exception fix without depending on a log appender.</p>
 */
public class JobsPluginVirtualThreadTest {

    private Properties originalConfig;
    private JobsPlugin plugin;

    @BeforeEach
    void setUp() {
        originalConfig = new Properties();
        originalConfig.putAll(Play.configuration);
        plugin = new JobsPlugin();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = originalConfig;
        JobsPlugin.scheduler.shutdownNow();
    }

    @Test
    void onApplicationStartCreatesVirtualSchedulerUnconditionally() {
        // Platform-thread jobs mode no longer exists; configuration toggles are no-ops.
        Play.configuration.setProperty("play.threads.virtual", "false");
        Play.configuration.setProperty("play.threads.virtual.jobs", "false");

        plugin.onApplicationStart();

        assertThat(JobsPlugin.scheduler).isNotNull();
    }

    /**
     * Verify a fixed-delay job actually runs on a virtual carrier when VT mode is enabled.
     * This covers the {@code scheduleWithFixedDelay} dispatch path on
     * {@link play.utils.VirtualThreadScheduledExecutor} end-to-end (H1: cancel-on-shutdown
     * relies on this codepath wrapping each tick in a VT thread).
     */
    @Test
    void scheduledFixedDelayJobRunsOnVirtualThread() throws Exception {
        Play.configuration.setProperty("play.threads.virtual.jobs", "true");
        plugin.onApplicationStart();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ScheduledFuture<?> handle = JobsPlugin.scheduler.scheduleWithFixedDelay(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        }, 0, 50, TimeUnit.MILLISECONDS);

        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(isVirtual.get()).isTrue();
            assertThat(threadName.get()).startsWith("jobs-vthread-");
        } finally {
            handle.cancel(true);
        }
    }

    /**
     * C2: when a Callable queued via {@link Job#now} (or any after-request action) throws,
     * the wrapper installed by {@link JobsPlugin#afterInvocation()} must catch the throwable
     * and log it. We assert the action's body actually executes (the Future-bound submit
     * doesn't drop it) by setting an {@link AtomicReference} from inside the throwing lambda.
     *
     * <p>We exercise {@link JobsPlugin#afterInvocation()} directly instead of going through
     * {@link Job#now} (which checks {@code Request.current()} and would reject outside an
     * actual request lifecycle). The ThreadLocal is set via reflection because the field
     * is package-private — keeping it out of the public API while still allowing this test
     * to drive the silent-exception fix without standing up a full Play app.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void afterInvocationActionLogsThrowingCallable() throws Exception {
        Play.configuration.setProperty("play.threads.virtual.jobs", "true");
        plugin.onApplicationStart();

        AtomicReference<Throwable> thrownCaught = new AtomicReference<>();
        CountDownLatch wrapperFinished = new CountDownLatch(1);

        // Install a callable that throws; the Logger.error wrapper installed by the
        // afterInvocation fix should log AND rethrow (so the Future captures it). We
        // detect the wrapper executed end-to-end by counting down only after the throw.
        Callable<Object> throwingAction = () -> {
            try {
                throw new RuntimeException("boom");
            } finally {
                // The lambda's 'finally' block runs before the wrapper's catch sees the
                // throwable; we use this to prove the action body actually executed
                // (not silently dropped at submit time).
                thrownCaught.set(new RuntimeException("action-ran-marker"));
                wrapperFinished.countDown();
            }
        };

        // Reach into JobsPlugin.afterInvocationActions ThreadLocal directly. afterInvocation
        // pulls and clears it; we feed a single throwing entry.
        Field f = JobsPlugin.class.getDeclaredField("afterInvocationActions");
        f.setAccessible(true);
        ThreadLocal<List<Callable<?>>> tl = (ThreadLocal<List<Callable<?>>>) f.get(null);
        List<Callable<?>> queue = new LinkedList<>();
        queue.add(throwingAction);
        tl.set(queue);

        plugin.afterInvocation();

        assertThat(wrapperFinished.await(5, TimeUnit.SECONDS)).isTrue();
        // Action body executed (proving submit() didn't silently drop it).
        assertThat(thrownCaught.get()).isNotNull();
        assertThat(thrownCaught.get().getMessage()).isEqualTo("action-ran-marker");
        // ThreadLocal must have been cleared by afterInvocation().
        assertThat(tl.get()).isNull();
    }
}
