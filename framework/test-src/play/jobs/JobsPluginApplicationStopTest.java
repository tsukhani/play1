package play.jobs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the PF-119 shutdown fix: {@code onApplicationStop()} no longer re-scans the
 * classloader ({@code getAssignableClasses(Job.class)} -> {@code getAllClasses()}) — which
 * took the {@code ApplicationClassloader} instance monitor and deadlocked against an in-flight
 * class load while {@code Play.stop()} held the {@code Play.class} monitor. Instead it iterates
 * the {@code @OnApplicationStop} subset captured at {@code afterApplicationStart()}.
 *
 * <p>The selection key ({@link JobsPlugin#selectApplicationStopJobs}) is the whole behavioral
 * change, so we test it directly rather than driving the lifecycle end-to-end: {@code Job.run()}
 * goes through the full Invocation lifecycle and {@code afterApplicationStart()} calls
 * {@code Play.classloader.getAllClasses()}, neither of which a hermetic unit test can stand up.
 * This mirrors {@link OnApplicationStartPriorityTest}'s approach to the ordering key.</p>
 */
public class JobsPluginApplicationStopTest {

    @OnApplicationStop
    static class StopA extends Job<Void> {}

    @OnApplicationStop
    static class StopB extends Job<Void> {}

    @OnApplicationStart
    static class StartOnly extends Job<Void> {}

    // No lifecycle annotation — an @On/@Every-only or plain job.
    static class Plain extends Job<Void> {}

    @Test
    void selectsOnlyApplicationStopJobs() {
        List<Class<?>> jobs = new ArrayList<>(List.of(
                StartOnly.class,
                StopA.class,
                Plain.class,
                StopB.class
        ));

        assertThat(JobsPlugin.selectApplicationStopJobs(jobs))
                .containsExactly(StopA.class, StopB.class);
    }

    @Test
    void preservesInputOrder() {
        // Reversed relative to the previous test to prove the subset keeps the caller's order
        // (afterApplicationStart() passes a priority-sorted list; the snapshot must not reshuffle).
        List<Class<?>> jobs = new ArrayList<>(List.of(StopB.class, StopA.class));

        assertThat(JobsPlugin.selectApplicationStopJobs(jobs))
                .containsExactly(StopB.class, StopA.class);
    }

    @Test
    void emptyWhenNoStopJobs() {
        assertThat(JobsPlugin.selectApplicationStopJobs(List.of(StartOnly.class, Plain.class)))
                .isEmpty();
    }
}
