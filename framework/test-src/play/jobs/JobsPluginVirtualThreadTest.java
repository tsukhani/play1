package play.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Invoker;
import play.Play;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
        if (JobsPlugin.usingVirtualThreads && JobsPlugin.virtualExecutor != null) {
            JobsPlugin.virtualExecutor.shutdownNow();
        } else if (JobsPlugin.executor != null) {
            JobsPlugin.executor.shutdownNow();
        }
        JobsPlugin.usingVirtualThreads = false;
        JobsPlugin.virtualExecutor = null;
        JobsPlugin.executor = null;
    }

    @Test
    void onApplicationStartCreatesPlatformExecutorByDefault() {
        Play.configuration.remove("play.threads.virtual");
        Play.configuration.remove("play.threads.virtual.jobs");

        plugin.onApplicationStart();

        assertThat(JobsPlugin.usingVirtualThreads).isFalse();
        assertThat(JobsPlugin.executor).isNotNull();
        assertThat(JobsPlugin.virtualExecutor).isNull();
    }

    @Test
    void onApplicationStartCreatesVirtualExecutorWhenEnabled() {
        Play.configuration.setProperty("play.threads.virtual", "true");

        plugin.onApplicationStart();

        assertThat(JobsPlugin.usingVirtualThreads).isTrue();
        assertThat(JobsPlugin.virtualExecutor).isNotNull();
        assertThat(JobsPlugin.executor).isNull();
    }

    @Test
    void jobExecutesOnVirtualThreadWhenEnabled() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        plugin.onApplicationStart();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        JobsPlugin.virtualExecutor.submit(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void scheduleWithFixedDelayPreservesSemantics() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        plugin.onApplicationStart();

        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        JobsPlugin.virtualExecutor.scheduleWithFixedDelay(() -> {
            count.incrementAndGet();
            latch.countDown();
        }, 0, 100, TimeUnit.MILLISECONDS);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void getStatusHandlesVirtualThreadMode() {
        Play.configuration.setProperty("play.threads.virtual", "true");
        plugin.onApplicationStart();

        String status = plugin.getStatus();
        assertThat(status).contains("Mode: virtual threads");
        assertThat(status).doesNotContain("Pool size:");
    }

    @Test
    void getStatusHandlesPlatformThreadMode() {
        Play.configuration.remove("play.threads.virtual");
        Play.configuration.remove("play.threads.virtual.jobs");
        plugin.onApplicationStart();

        String status = plugin.getStatus();
        assertThat(status).contains("Pool size:");
        assertThat(status).doesNotContain("Mode: virtual threads");
    }
}
