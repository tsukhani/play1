package play.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PF-260: what the build's check says, from the files {@link RunCompletionListener} and the build leave. */
public class RunCompletionTest {

    @Test
    public void aRunThatEndedAtItsEndPasses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".run");
        Files.writeString(RunCompletion.sibling(file, ".progress"), "started a.ATest\nfinished a.ATest\nskipped b.BTest\n");
        Files.writeString(RunCompletion.sibling(file, ".selected"),
                dir.resolve("a/ATest.class") + "\n" + dir.resolve("b/BTest.class") + "\n");
        Files.writeString(file, "ended after its last test class\n");

        assertThat(RunCompletion.problem(file, "ant unittest", dir)).isNull();
    }

    @Test
    public void aJvmThatEndedBetweenTwoClassesFailsThoughItWroteTheCompletionFile(@TempDir Path dir) throws IOException {
        // A stray System.exit, or a SIGTERM, while no class runs: the shutdown hook finds nothing running.
        Path classes = dir.resolve("test-classes"), file = dir.resolve(".run");
        Files.writeString(RunCompletion.sibling(file, ".progress"), "started a.ATest\nfinished a.ATest\n");
        Files.writeString(RunCompletion.sibling(file, ".selected"),
                classes.resolve("a/ATest.class") + "\n" + classes.resolve("b/BTest.class") + "\n");
        Files.writeString(file, "ended after its last test class\n");

        assertThat(RunCompletion.problem(file, "ant unittest", classes)).isEqualTo(
                "ant unittest: 1 of 2 selected test classes never started: b.BTest. Either the test JVM ended between"
                        + " two classes, which junitlauncher counts as a success, or a class has no tests for JUnit to run"
                        + " on its own (a non-static @Nested class, say), which no *Test class may be.");
    }

    @Test
    public void aRunThatNeverBeganSaysSoAndHowTheListenerIsWired(@TempDir Path dir) {
        String problem = RunCompletion.problem(dir.resolve(".run"), "ant unittest", dir);

        assertThat(problem).startsWith("ant unittest: the test JVM ended before its run began, so no test ran,")
                .contains("RunCompletionListener").contains("-Dplay.tests.completion");
    }

    @Test
    public void namesTheClassThatWasRunningAndTheSelectedOnesThatNeverStarted(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("test-classes"), file = dir.resolve(".run");
        Files.writeString(RunCompletion.sibling(file, ".progress"),
                "started a.ATest\nfinished a.ATest\nskipped d.DTest\nstarted b.BTest\n");
        Files.writeString(RunCompletion.sibling(file, ".selected"), String.join("\n",
                classes.resolve("a/ATest.class").toString(), classes.resolve("b/BTest.class").toString(),
                classes.resolve("c/CTest.class").toString(), classes.resolve("d/DTest.class").toString(),
                classes.resolve("e/Outer$InnerTest.class").toString(), ""));

        assertThat(RunCompletion.problem(file, "ant integration-test", classes)).isEqualTo(
                "ant integration-test: the test JVM ended before its run finished, so 3 of 5 test classes did not report."
                        + " Running when it ended: b.BTest. Never started: c.CTest, e.Outer$InnerTest."
                        + " Junitlauncher counts such a run as a success unless the JVM's exit status was 1, 2 or 3.");
    }

    @Test
    public void aRunThatEndedAfterEveryClassReportedStillFails(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".run");
        Files.writeString(RunCompletion.sibling(file, ".progress"), "started a.ATest\nfinished a.ATest\n");
        Files.writeString(RunCompletion.sibling(file, ".selected"), dir.resolve("a/ATest.class") + "\n");

        assertThat(RunCompletion.problem(file, "ant unittest", dir))
                .isEqualTo("ant unittest: the test JVM ended before its run finished, after all 1 test classes had reported."
                        + " Junitlauncher counts that as a success.");
    }
}
