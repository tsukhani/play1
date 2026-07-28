package play.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PF-169: the Nuxt SPA build is the registered `playFrontendSpa` task that both
 * packaging tasks depend on, not a helper called from inside their actions.
 *
 * Gradle's run-each-task-at-most-once rule applies to the task graph, so a call
 * made from an action body escapes it: `gradle playDist playBundle` used to run
 * a full Nuxt production build twice. These tests assert the graph shape, which
 * needs neither pnpm nor Nuxt installed.
 */
class FrontendSpaTaskTest {

    /** Count of task-graph lines for [task] in `--dry-run` output (e.g. ":playFrontendSpa SKIPPED"). */
    private fun graphOccurrences(output: String, task: String): Int =
        output.lineSequence().count { it.trim().substringBefore(' ') == ":$task" }

    /**
     * The packaging tasks pull in playPrecompile, which needs frameworkPath just to
     * compute its dependencies. --dry-run executes nothing, so the path need not exist.
     */
    private fun writePackagingProject(dir: File) =
        TestProject.write(dir, play1Block = """    frameworkPath.set(file("fake-framework"))""")

    @Test
    fun `one invocation naming both packaging tasks builds the SPA exactly once`(@TempDir tmp: File) {
        writePackagingProject(tmp)

        val result = TestProject.runner(tmp, "playDist", "playBundle", "--dry-run").build()

        // An execution plan lists each task once by construction, so the teeth here
        // are the count being 1 rather than 0: the SPA build must be a graph node
        // both tasks depend on. Reverting to a call from inside the action bodies
        // makes it vanish from the plan — and run twice — and this fails.
        assertEquals(
            1, graphOccurrences(result.output, "playFrontendSpa"),
            "PF-169: `gradle playDist playBundle` must schedule playFrontendSpa exactly once.\n${result.output}"
        )
        // Sanity that the graph really did contain both packaging tasks — otherwise
        // "exactly once" could pass on a graph that ran neither.
        assertEquals(1, graphOccurrences(result.output, "playDist"), result.output)
        assertEquals(1, graphOccurrences(result.output, "playBundle"), result.output)
    }

    @Test
    fun `each packaging task depends on the SPA build on its own`(@TempDir tmp: File) {
        writePackagingProject(tmp)

        // Criterion 2's graph half: run individually, each must still pull in the
        // SPA build (the packaging actions force-include public/spa into the zip).
        listOf("playDist", "playBundle").forEach { task ->
            val result = TestProject.runner(tmp, task, "--dry-run").build()
            assertEquals(
                1, graphOccurrences(result.output, "playFrontendSpa"),
                "$task on its own must still schedule playFrontendSpa.\n${result.output}"
            )
        }
    }

    @Test
    fun `an app with no frontend directory skips the SPA build`(@TempDir tmp: File) {
        TestProject.write(tmp)

        val result = TestProject.runner(tmp, "playFrontendSpa").build()

        assertEquals(
            TaskOutcome.SKIPPED, result.task(":playFrontendSpa")?.outcome,
            "Without a frontend/ directory the onlyIf must skip the task, matching the " +
                "helper's pre-PF-169 early return.\n${result.output}"
        )
    }

    @Test
    fun `an app with a frontend directory runs the SPA build`(@TempDir tmp: File) {
        TestProject.write(tmp)
        File(tmp, "frontend").mkdirs()

        // Mutation guard on the onlyIf predicate: an inverted or misdirected
        // predicate would silently never build the SPA and ship a frontend-less
        // release zip with no error — the exact class of bug PF-169 guards.
        //
        // The action is expected to FAIL here, deterministically and either way:
        // without pnpm on PATH the pre-flight probe throws the actionable
        // "pnpm not found" GradleException; with pnpm on PATH, `pnpm install`
        // against a bare directory has no manifest to install. What is asserted
        // is only that the task was not skipped.
        val result = TestProject.runner(tmp, "playFrontendSpa").buildAndFail()

        assertEquals(
            TaskOutcome.FAILED, result.task(":playFrontendSpa")?.outcome,
            "With frontend/ present the onlyIf must let the task run.\n${result.output}"
        )
        assertTrue(
            result.output.contains("frontend"),
            "The failure should reference the frontend directory it tried to build.\n${result.output}"
        )
    }
}
