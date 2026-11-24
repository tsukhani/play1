package play.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;
import play.PlayBuilder;
import play.jobs.JobsPlugin;
import play.plugins.PluginCollection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-254: the interrupt status goes with a call that {@link TestClassJob} hands to the job's
 * thread, as if the call ran on the calling thread (PF-230). The status the caller hands a call
 * over with used to reach the call in the caller's wait, after the call was queued, and the job's
 * thread could run the call before that: then the code started without the status, as a test did
 * on CI that reads the status its {@code @BeforeEach} left on its first line.
 * {@link TestClassJob#afterQueue} holds the caller after the add until the code has run, which is
 * that order every time.
 */
public class TestClassJobTest {

    private PluginCollection savedPlugins;
    private Play.Mode savedMode;
    private boolean savedStarted;
    private TestClassJob job;

    @BeforeEach
    void setUp() {
        new PlayBuilder().build();
        savedPlugins = Play.pluginCollection;
        savedMode = Play.mode;
        savedStarted = Play.started;
        Play.pluginCollection = new PluginCollection();
        // The job's invocation runs as in a test JVM whose application has started. In DEV, or not
        // started, its init() would detect changes and start Play here, which this test has not set up.
        Play.mode = Play.Mode.PROD;
        Play.started = true;
        new JobsPlugin().onApplicationStart();
        job = new TestClassJob();
    }

    @AfterEach
    void tearDown() {
        TestClassJob.afterQueue = null;
        Thread.interrupted();
        job.end();
        JobsPlugin.scheduler.shutdownNow();
        JobsPlugin.scheduledJobs.clear();
        Play.started = savedStarted;
        Play.mode = savedMode;
        Play.pluginCollection = savedPlugins;
    }

    /** Hold the calling thread after the add until the code has run, without an interruptible wait. */
    private static void holdTheCallerUntil(CountDownLatch ran) {
        TestClassJob.afterQueue = () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ran.getCount() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        };
    }

    @Test
    void theStatusACallIsHandedOverWithReachesItsCodeEvenWhenTheJobsThreadRunsTheCallFirst() throws Throwable {
        CountDownLatch ran = new CountDownLatch(1);
        holdTheCallerUntil(ran);

        Thread.currentThread().interrupt();
        boolean seen = job.run(() -> {
            boolean interrupted = Thread.interrupted();
            ran.countDown();
            return interrupted;
        });

        assertThat(seen).as("the code starts with the status the call was handed over with").isTrue();
        assertThat(Thread.currentThread().isInterrupted()).as("the code cleared it, so the caller leaves clear").isFalse();
    }

    @Test
    void theStatusABeforeEachLeavesReachesTheTestThatFollows() throws Throwable {
        job.run(() -> {
            Thread.currentThread().interrupt();
            return null;
        });
        assertThat(Thread.currentThread().isInterrupted()).as("the caller leaves with the status the code left").isTrue();

        CountDownLatch ran = new CountDownLatch(1);
        holdTheCallerUntil(ran);
        boolean seen = job.run(() -> {
            boolean interrupted = Thread.interrupted();
            ran.countDown();
            return interrupted;
        });

        assertThat(seen).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aCallHandedOverWithoutTheStatusRunsWithoutIt() throws Throwable {
        CountDownLatch ran = new CountDownLatch(1);
        holdTheCallerUntil(ran);

        boolean seen = job.run(() -> {
            boolean interrupted = Thread.currentThread().isInterrupted();
            ran.countDown();
            return interrupted;
        });

        assertThat(seen).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
