package play.jobs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers PF-131: an {@code @Every} job must keep running after a single run throws.
 *
 * <p>{@code scheduleWithFixedDelay} (matching {@code ScheduledThreadPoolExecutor} semantics)
 * drives a {@code Runnable} that throws to a terminal state and never reschedules it, so one
 * transient failure used to permanently disable the job. The fix wraps the job in
 * {@link JobsPlugin#resilient(Job)}, which catches and logs the throwable instead of letting
 * it reach the executor. We exercise that wrapper directly rather than driving a real
 * scheduler: the whole behavioral change is "the Runnable handed to the executor never
 * throws", so invoking it twice (first run throws, second run succeeds) is both faithful and
 * deterministic — no timing, no booted app. This mirrors {@code OnApplicationStartPriorityTest}'s
 * approach of testing the package-private behavioral hook directly.</p>
 */
public class JobsPluginEveryResilienceTest {

    /** A job whose first invocation throws and whose subsequent invocations succeed. */
    static class FlakyJob extends Job<Void> {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public void run() {
            int n = runs.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("transient DB blip on run " + n);
            }
        }
    }

    @Test
    void resilientWrapperSwallowsExceptionSoFixedDelayScheduleSurvives() {
        FlakyJob job = new FlakyJob();
        Runnable wrapped = JobsPlugin.resilient(job);

        // First run throws internally — but the wrapper handed to scheduleWithFixedDelay
        // must NOT propagate it, or the executor would drop the periodic task forever.
        assertThatCode(wrapped::run).doesNotThrowAnyException();
        assertThat(job.runs.get()).isEqualTo(1);

        // The schedule is still alive, so the next tick runs again and now succeeds.
        assertThatCode(wrapped::run).doesNotThrowAnyException();
        assertThat(job.runs.get()).isEqualTo(2);
    }
}
