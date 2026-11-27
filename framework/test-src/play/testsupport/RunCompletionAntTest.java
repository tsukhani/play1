package play.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PF-260: a scratch build, run by the Ant that runs this suite, around a real forked junitlauncher, with the macros of
 * {@code framework/run-completion.xml} that the framework's and the modules' builds use. A JVM that a test ends,
 * which junitlauncher counts as a success, must fail it and be named.
 */
public class RunCompletionAntTest {

    private static final Path FRAMEWORK = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    private static final String FIXTURES = "play/testsupport/RunCompletionFixtures$";

    private record Run(int exit, String output) {
    }

    @Test
    public void aRunThatFinishesPasses(@TempDir Path dir) throws Exception {
        Run run = ant(dir, "Completes");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.output()).contains("BUILD SUCCESSFUL");
        assertThat(dir.resolve(".run")).as("the listener's word that the run finished").exists();
    }

    @Test
    public void aJvmThatHaltsFailsTheBuildAndNamesWhatDidNotRun(@TempDir Path dir) throws Exception {
        // Junitlauncher runs each class as a test plan of its own, in the order Ant's scan lists their files, which
        // the file system decides: whichever ran before the halt reported, the rest never started.
        Run run = ant(dir, "Completes", "HaltsTheJvm", "NeverRuns");

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output()).contains("BUILD FAILED")
                .contains("scratch: the test JVM ended before its run finished, so ")
                .contains(" of 3 test classes did not report.")
                .contains("Running when it ended: play.testsupport.RunCompletionFixtures$HaltsTheJvm.");
        String progress = Files.readString(dir.resolve(".run.progress"), StandardCharsets.UTF_8);
        for (String fixture : List.of("Completes", "NeverRuns")) {
            String className = "play.testsupport.RunCompletionFixtures$" + fixture;
            if (progress.contains("finished " + className)) {
                assertThat(run.output()).as("%s reported", className).doesNotContain(className + ",").doesNotContain(className + ".");
            } else {
                assertThat(progress).as("%s started, so it would have reported", className).doesNotContain("started " + className);
                assertThat(run.output()).as("%s never started", className).contains("Never started: ").contains(className);
            }
        }
    }

    @Test
    public void aJvmThatExitsWithStatusZeroFailsTheBuildToo(@TempDir Path dir) throws Exception {
        Run run = ant(dir, "ExitsCleanly");

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output()).contains("BUILD FAILED")
                .contains("Running when it ended: play.testsupport.RunCompletionFixtures$ExitsCleanly.");
    }

    /** Run the scratch build on the fixture classes named, through Ant's own launcher on this JVM's java. */
    private static Run ant(Path dir, String... fixtures) throws IOException, InterruptedException {
        String antHome = System.getProperty("ant.home");
        assumeTrue(antHome != null && Files.isRegularFile(Path.of(antHome, "lib", "ant-launcher.jar")),
                "runs under Ant, which passes ant.home to the test fork");
        Path build = dir.resolve("build.xml");
        Files.writeString(build, String.join("\n",
                "<project name=\"pf260-scratch\" default=\"run\" basedir=\".\">",
                "  <import file=\"${framework}/run-completion.xml\"/>",
                "  <target name=\"run\">",
                "    <fileset id=\"tests\" dir=\"${framework}/test-classes\" includes=\"${fixtures}\"/>",
                "    <run-completion-start file=\"${completion}\"/>",
                "    <junitlauncher haltonfailure=\"false\" failureproperty=\"failed\" printSummary=\"true\">",
                "      <classpath>",
                "        <pathelement location=\"${framework}/test-support\"/>",
                "        <pathelement location=\"${framework}/test-classes\"/>",
                "        <fileset dir=\"${framework}/lib\" includes=\"junit-*.jar,opentest4j-*.jar,apiguardian-*.jar\"/>",
                "      </classpath>",
                "      <testclasses>",
                "        <fileset refid=\"tests\"/>",
                "        <fork>",
                "          <sysproperty key=\"play.tests.completion\" value=\"${completion}\"/>",
                "          <sysproperty key=\"junit.jupiter.testclass.order.default\" value=\"org.junit.jupiter.api.ClassOrderer$ClassName\"/>",
                "        </fork>",
                "      </testclasses>",
                "    </junitlauncher>",
                "    <run-completion-check file=\"${completion}\" label=\"scratch\" testclasses=\"${framework}/test-support\"",
                "                          root=\"${framework}/test-classes\">",
                "      <selected><fileset refid=\"tests\"/></selected>",
                "    </run-completion-check>",
                "    <fail if=\"failed\" message=\"tests failed\"/>",
                "  </target>",
                "</project>",
                ""), StandardCharsets.UTF_8);
        List<String> patterns = new ArrayList<>();
        for (String fixture : fixtures) {
            patterns.add(FIXTURES + fixture + ".class");
        }
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of(antHome, "lib", "ant-launcher.jar").toString(),
                "-Dant.home=" + antHome,
                "org.apache.tools.ant.launch.Launcher",
                "-buildfile", build.toString(),
                "-Dframework=" + FRAMEWORK,
                "-Dcompletion=" + dir.resolve(".run"),
                "-Dfixtures=" + String.join(",", patterns));
        Path out = dir.resolve("ant.out");
        Process ant = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        boolean ended = ant.waitFor(120, TimeUnit.SECONDS);
        if (!ended) {
            ant.destroyForcibly().waitFor();
        }
        String output = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(ended).as("the scratch build ended:%n%s", output).isTrue();
        return new Run(ant.exitValue(), output);
    }
}
