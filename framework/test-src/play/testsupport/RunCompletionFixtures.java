package play.testsupport;

import org.junit.jupiter.api.Test;

/**
 * PF-260: the test classes {@link RunCompletionAntTest} runs in a scratch junitlauncher fork. None of their names
 * ends in {@code Test}, so the suites' {@code **}{@code /*Test.class} patterns never select them: one of them ends
 * the JVM it runs in.
 */
public final class RunCompletionFixtures {

    private RunCompletionFixtures() {
    }

    public static class Completes {
        @Test
        public void passes() {
        }
    }

    /** Ends its JVM as a crash or a kill does: no shutdown hook, an exit status junitlauncher ignores. */
    public static class HaltsTheJvm {
        @Test
        public void halts() {
            Runtime.getRuntime().halt(7);
        }
    }

    /** Ordered after {@link HaltsTheJvm} by class name, so it never starts. */
    public static class NeverRuns {
        @Test
        public void passes() {
        }
    }

    /** Ends its JVM with status 0, which junitlauncher reads as a run that passed. */
    public static class ExitsCleanly {
        @Test
        public void exits() {
            System.exit(0);
        }
    }
}
