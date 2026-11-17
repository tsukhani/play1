package play.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PF-207: an application's pnpm follows the framework's. playFrontendSpa warns when
 * frontend/package.json pins another version, and playPnpmSync moves the pin and runs
 * `pnpm install`, which is what rewrites pnpm-lock.yaml's packageManagerDependencies.
 *
 * A stub pnpm stands in for the real one: it records each call and, for `run generate`,
 * leaves the .output/public that playFrontendSpa copies. A `#!/bin/sh` stub is not
 * something ProcessBuilder can start on Windows, hence the OS condition on those cases.
 */
class PnpmSyncTaskTest {

    private val framework = frameworkPnpmVersion()

    private val warning = "but this framework release builds frontends with pnpm $framework"

    private fun app(dir: File, packageJson: String): File {
        TestProject.write(dir)
        File(dir, "frontend").mkdirs()
        return File(dir, "frontend/package.json").apply { writeText(packageJson) }
    }

    /**
     * A runner whose pnpm tasks run a stub that appends "<dir name> <args>" to the returned log.
     * It is set through the tasks' pnpm property, not PATH: the JDK looks a bare command up on
     * the PATH the TestKit daemon started with, which withEnvironment does not change.
     */
    private fun withStubPnpm(dir: File, vararg args: String): Pair<GradleRunner, File> {
        val log = File(dir, "pnpm-calls.log")
        val stub = File(dir, "stub-bin/pnpm").apply {
            parentFile.mkdirs()
            writeText(
                """
                #!/bin/sh
                # PF-214: playFrontendSpa's ssr mode check, `pnpm exec node <script> <frontend>`;
                # this one says a single-page app, so these cases build the static site as before.
                if [ "${'$'}1 ${'$'}2" = "exec node" ]; then
                    echo "${'$'}(basename "${'$'}(pwd)") exec node" >> "${log.absolutePath}"
                    echo '{"ssr":false,"explicit":true}'
                    exit 0
                fi
                echo "${'$'}(basename "${'$'}(pwd)") ${'$'}*" >> "${log.absolutePath}"
                if [ "${'$'}1" = run ] && [ "${'$'}2" = generate ]; then
                    mkdir -p .output/public && echo ok > .output/public/index.html
                fi
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        File(dir, "build.gradle.kts").appendText(
            """
            tasks.withType<play.gradle.PlayFrontendSpaTask>().configureEach { pnpm.set("${stub.absolutePath}") }
            tasks.withType<play.gradle.PlayPnpmSyncTask>().configureEach { pnpm.set("${stub.absolutePath}") }
            """.trimIndent() + "\n"
        )
        return TestProject.runner(dir, *args) to log
    }

    private fun warnings(output: String) = output.lineSequence().count { warning in it }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs a #!/bin/sh stub pnpm")
    fun `the SPA build warns once about a pin other than the framework's and still builds`(@TempDir tmp: File) {
        app(tmp, "{\n  \"name\": \"app-frontend\",\n  \"packageManager\": \"pnpm@1.0.0\"\n}\n")
        val (runner, log) = withStubPnpm(tmp, "playFrontendSpa")

        val result = runner.build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":playFrontendSpa")?.outcome, result.output)
        assertEquals(1, warnings(result.output), result.output)
        assertTrue("frontend/package.json pins \"pnpm@1.0.0\"" in result.output, result.output)
        assertTrue("play pnpm-sync" in result.output, "the warning names the fix\n${result.output}")
        assertEquals(listOf("frontend --version", "frontend install", "frontend exec node", "frontend run generate"), log.readLines())
        assertTrue(File(tmp, "public/spa/index.html").isFile, "the build went on to copy the SPA")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs a #!/bin/sh stub pnpm")
    fun `the SPA build warns about a frontend that pins no pnpm`(@TempDir tmp: File) {
        app(tmp, "{\n  \"name\": \"app-frontend\"\n}\n")
        val (runner, _) = withStubPnpm(tmp, "playFrontendSpa")

        val result = runner.build()

        assertEquals(1, warnings(result.output), result.output)
        assertTrue("frontend/package.json pins no pnpm" in result.output, result.output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs a #!/bin/sh stub pnpm")
    fun `the SPA build is quiet when the pin is the framework's`(@TempDir tmp: File) {
        app(tmp, "{\n  \"name\": \"app-frontend\",\n  \"packageManager\": \"pnpm@$framework\"\n}\n")
        val (runner, _) = withStubPnpm(tmp, "playFrontendSpa")

        val result = runner.build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":playFrontendSpa")?.outcome, result.output)
        assertEquals(0, warnings(result.output), result.output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs a #!/bin/sh stub pnpm")
    fun `the sync moves the pin and runs pnpm install in the frontend`(@TempDir tmp: File) {
        val packageJson = app(tmp, "{\n  \"name\": \"app-frontend\",\n  \"packageManager\": \"pnpm@1.0.0\",\n  \"private\": true\n}\n")
        val (runner, log) = withStubPnpm(tmp, "playPnpmSync")

        val result = runner.build()

        assertEquals(
            "{\n  \"name\": \"app-frontend\",\n  \"packageManager\": \"pnpm@$framework\",\n  \"private\": true\n}\n",
            packageJson.readText(),
        )
        // The install is what rewrites pnpm-lock.yaml; without it --frozen-lockfile fails.
        assertEquals(listOf("frontend --version", "frontend install"), log.readLines())
        assertTrue("Commit frontend/package.json and frontend/pnpm-lock.yaml together" in result.output, result.output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs a #!/bin/sh stub pnpm")
    fun `the sync adds a missing pin and still installs when the pin is already right`(@TempDir tmp: File) {
        val packageJson = app(tmp, "{\n  \"name\": \"app-frontend\"\n}\n")
        val (runner, log) = withStubPnpm(tmp, "playPnpmSync")

        runner.build()
        assertEquals("{\n  \"packageManager\": \"pnpm@$framework\",\n  \"name\": \"app-frontend\"\n}\n", packageJson.readText())

        // Run again: nothing to write, but a pin edited by hand without an install leaves the
        // lockfile behind, so the install runs every time.
        val again = runner.build()
        assertTrue("already pins pnpm $framework" in again.output, again.output)
        assertEquals(listOf("frontend --version", "frontend install", "frontend --version", "frontend install"), log.readLines())
    }

    @Test
    fun `the sync fails on an application without a frontend`(@TempDir tmp: File) {
        TestProject.write(tmp)

        val result = TestProject.runner(tmp, "playPnpmSync").buildAndFail()

        // The message names the file by its absolute path, with the platform's separator.
        assertTrue("This application has no" in result.output && "frontend${File.separator}package.json" in result.output, result.output)
    }
}
