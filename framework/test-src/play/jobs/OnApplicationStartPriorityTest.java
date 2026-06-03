package play.jobs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the @OnApplicationStart {@code priority()} ordering added to {@link JobsPlugin}.
 *
 * <p>{@code afterApplicationStart()} sorts discovered job classes with
 * {@code Comparator.comparingInt(JobsPlugin::startPriority)} before running them, so jobs
 * execute in ascending priority (0 first). We assert on that exact comparator rather than
 * driving the jobs end-to-end: {@code Job.run()} goes through the full Invocation lifecycle
 * and calls {@code Play.start()} when the app isn't booted, which a unit test must not do.
 * The ordering key ({@link JobsPlugin#startPriority}) is the whole behavioral change, so
 * testing it directly is both faithful and hermetic.</p>
 */
public class OnApplicationStartPriorityTest {

    @OnApplicationStart(priority = 0)
    static class HighA extends Job<Void> {}

    @OnApplicationStart(priority = 0)
    static class HighB extends Job<Void> {}

    @OnApplicationStart // priority defaults to 0
    static class DefaultPriority extends Job<Void> {}

    @OnApplicationStart(priority = 5)
    static class Mid extends Job<Void> {}

    @OnApplicationStart(priority = 10)
    static class Low extends Job<Void> {}

    // No @OnApplicationStart — represents an @On/@Every-only job.
    static class NotAStartJob extends Job<Void> {}

    @Test
    void startPriorityReflectsAnnotation() {
        assertThat(JobsPlugin.startPriority(DefaultPriority.class)).isEqualTo(0);
        assertThat(JobsPlugin.startPriority(Mid.class)).isEqualTo(5);
        assertThat(JobsPlugin.startPriority(Low.class)).isEqualTo(10);
    }

    @Test
    void nonStartJobsSortLast() {
        assertThat(JobsPlugin.startPriority(NotAStartJob.class)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void jobsRunInAscendingPriorityWithStableTies() {
        // Scrambled input; priority-0 jobs in a deliberate relative order to prove the sort
        // is stable (equal priorities keep their original order — the JavaDoc contract).
        List<Class<?>> jobs = new ArrayList<>(List.of(
                Low.class,             // 10
                DefaultPriority.class, // 0
                NotAStartJob.class,    // MAX_VALUE
                Mid.class,             // 5
                HighB.class,           // 0
                HighA.class            // 0
        ));

        jobs.sort(Comparator.comparingInt(JobsPlugin::startPriority));

        assertThat(jobs).containsExactly(
                DefaultPriority.class, // 0 — kept ahead of HighB/HighA (stable tie-break)
                HighB.class,           // 0
                HighA.class,           // 0
                Mid.class,             // 5
                Low.class,             // 10
                NotAStartJob.class     // MAX_VALUE — non-start job last
        );
    }
}
