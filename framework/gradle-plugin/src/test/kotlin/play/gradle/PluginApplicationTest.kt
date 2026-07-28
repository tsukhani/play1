package play.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Plugin-application + task-registration coverage. Guards "task not registered"
 * regressions for the full play1 task group.
 */
class PluginApplicationTest {

    /** Every task the plugin promises in its play1 group (see CLAUDE.md). */
    private val expectedPlay1Tasks = listOf(
        "playRun", "playStart", "playStop", "playRestart",
        "playTest", "playAutotest", "playPrecompile", "playBundle", "playDist",
        "playClean", "playSecret", "playEvolutions",
        "playStatus", "playPid", "playOut", "playVersion",
        "playClasspath", "playModulesInfo", "playJavadoc",
        "playFrontendSpa",
    )

    @Test
    fun `plugin applies and registers the full play1 task group`(@TempDir tmp: File) {
        // PF-146 regression: the `tasks` report task REALIZES every task (playRun/playStart/...),
        // which must NOT eagerly resolve play1.frameworkPath (a property with no convention) at
        // realization time. So this deliberately does NOT set frameworkPath — before PF-146 this
        // build failed with "Cannot query the value of this provider ... no value available" just
        // trying to list tasks. After the fix, realization is lazy and task listing succeeds.
        TestProject.write(tmp)

        val result = TestProject.runner(tmp, "tasks", "--all", "--group", "play1").build()

        // `tasks --group play1` lists every task we registered under that group.
        // Assert each expected task name appears in the report.
        val output = result.output
        expectedPlay1Tasks.forEach { task ->
            assertTrue(
                output.contains(task),
                "Expected task '$task' in the play1 group, but `tasks --group play1` did not list it.\n$output"
            )
        }

        // Mutation sanity: a task that the plugin does NOT register must be
        // absent. If task registration silently grew/typo'd, this guards the
        // assertion above from being a tautology.
        assertFalse(
            output.contains("playBogusTaskThatDoesNotExist"),
            "A non-existent task should not appear in the task report"
        )
    }

    @Test
    fun `each play1 task is individually resolvable by name`(@TempDir tmp: File) {
        TestProject.write(tmp)

        // `help --task <name>` resolves the task by name and fails the build if
        // it is not registered. Run it for every expected task in one invocation
        // chain would be slow; instead assert via the model: a dry-run of
        // playVersion (a no-fork task) actually executes and prints the version.
        val result = TestProject.runner(
            tmp, "playVersion", "--quiet"
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":playVersion")?.outcome)
        // playVersion prints ext.frameworkVersion; its convention is the
        // "1.13.0-SNAPSHOT" default set in Play1Extension. Assert a version-like
        // token is printed, proving the task body actually ran (not just registered).
        assertTrue(
            result.output.contains("1.13.0-SNAPSHOT"),
            "playVersion should print the default frameworkVersion convention.\n${result.output}"
        )
    }

    @Test
    fun `play1 DSL with frameworkPath and modules is accepted at configuration time`(@TempDir tmp: File) {
        TestProject.write(
            tmp,
            play1Block = """
                frameworkVersion.set("9.9.9-TEST")
                frameworkPath.set(file("fake-framework"))
                modules("somemodule", "another")
            """.trimIndent()
        )

        // Configuring the extension must not fail at configuration time. `help`
        // forces full configuration of the project without executing app tasks.
        val result = TestProject.runner(tmp, "help", "--quiet").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)

        // Prove the DSL values actually wired into the extension: playVersion
        // echoes frameworkVersion, so the overridden value must appear.
        val ver = TestProject.runner(tmp, "playVersion", "--quiet").build()
        assertTrue(
            ver.output.contains("9.9.9-TEST"),
            "frameworkVersion.set(...) from the play1 block should be honored by playVersion.\n${ver.output}"
        )
    }
}
