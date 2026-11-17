package play.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PF-210: playFrontendSpa builds Nuxt's server (`pnpm run build`, left at frontend/.output) for a
 * frontend whose config sets ssr: true itself, and the static site (`pnpm run generate`, copied
 * to public/spa) otherwise, an absent key included, which Nuxt resolves to true.
 *
 * A stub node stands in for the mode check: it prints the mode line a test leaves in
 * frontend/.stub-ssr-mode. A stub pnpm records each call and leaves what `run build` or
 * `run generate` would. Both are `#!/bin/sh` scripts, hence the OS condition.
 */
class NuxtSsrBuildTest {

    private fun app(dir: File, mode: String?): File {
        TestProject.write(dir)
        val frontend = File(dir, "frontend").apply { mkdirs() }
        File(frontend, "package.json").writeText("{\n  \"name\": \"app-frontend\",\n  \"packageManager\": \"pnpm@${frameworkPnpmVersion()}\"\n}\n")
        if (mode != null) File(frontend, ".stub-ssr-mode").writeText(mode + "\n")
        return frontend
    }

    /** A runner whose frontend tasks run the stub pnpm and node; returns it with pnpm's call log. */
    private fun withStubs(dir: File, vararg args: String): Pair<GradleRunner, File> {
        val log = File(dir, "pnpm-calls.log")
        val bin = File(dir, "stub-bin").apply { mkdirs() }
        val pnpm = File(bin, "pnpm").apply {
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> "${log.absolutePath}"
                if [ "${'$'}1 ${'$'}2" = "run build" ]; then
                    mkdir -p .output/public .output/server
                    [ -f .stub-no-server ] || echo "// server" > .output/server/index.mjs
                fi
                if [ "${'$'}1 ${'$'}2" = "run generate" ]; then
                    mkdir -p .output/public && echo ok > .output/public/index.html
                fi
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        val node = File(bin, "node").apply {
            writeText(
                """
                #!/bin/sh
                if [ -f "${'$'}2/.stub-node-fail" ]; then echo "Cannot find module 'nuxt/package.json'" >&2; exit 1; fi
                echo "a line the config printed"
                cat "${'$'}2/.stub-ssr-mode"
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        File(dir, "build.gradle.kts").appendText(
            """
            tasks.withType<play.gradle.PlayFrontendSpaTask>().configureEach {
                pnpm.set("${pnpm.absolutePath}")
                node.set("${node.absolutePath}")
            }
            """.trimIndent() + "\n"
        )
        return TestProject.runner(dir, *args) to log
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs #!/bin/sh stubs")
    fun `an explicit ssr true builds Nuxt's server and drops a stale public spa`(@TempDir tmp: File) {
        app(tmp, """{"ssr":true,"explicit":true,"node":"^20.19.0 || >=22.12.0"}""")
        File(tmp, "public/spa").mkdirs()
        File(tmp, "public/spa/index.html").writeText("left by an earlier static build")
        val (runner, log) = withStubs(tmp, "playFrontendSpa")

        val result = runner.build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":playFrontendSpa")?.outcome, result.output)
        assertEquals(listOf("--version", "install", "run build"), log.readLines())
        assertTrue(frontendServerEntry(tmp).isFile, "Nuxt's server stays at frontend/.output")
        assertFalse(File(tmp, "public/spa").exists(), "a stale static build must not ship beside the server")
        assertTrue("sets ssr: true: building Nuxt's server" in result.output, result.output)
        // PF-212: what the launchers run it with, and the Node it needs, ship beside it.
        val output = File(tmp, "frontend/.output")
        assertEquals(
            javaClass.getResource("/play/gradle/play1-server.mjs")!!.readText(),
            File(output, "play1-server.mjs").readText(),
        )
        assertEquals("{\"node\": \"^20.19.0 || >=22.12.0\"}\n", File(output, "play1.json").readText())
        assertTrue("(needs Node ^20.19.0 || >=22.12.0)" in result.output, result.output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs #!/bin/sh stubs")
    fun `an ssr Nuxt resolves to true from an absent key builds the static site and says how to get the server`(@TempDir tmp: File) {
        app(tmp, """{"ssr":true,"explicit":false}""")
        val (runner, log) = withStubs(tmp, "playFrontendSpa")

        val result = runner.build()

        assertEquals(listOf("--version", "install", "run generate"), log.readLines())
        assertTrue(File(tmp, "public/spa/index.html").isFile)
        assertFalse(frontendServerEntry(tmp).exists())
        assertTrue("does not set ssr, so this builds the static site; set ssr: true" in result.output, result.output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs #!/bin/sh stubs")
    fun `ssr false builds the static site as before`(@TempDir tmp: File) {
        app(tmp, """{"ssr":false,"explicit":true}""")
        val (runner, log) = withStubs(tmp, "playFrontendSpa")

        val result = runner.build()

        assertEquals(listOf("--version", "install", "run generate"), log.readLines())
        assertTrue(File(tmp, "public/spa/index.html").isFile)
        assertFalse("ssr" in result.output.lineSequence().filter { it.startsWith("~ frontend/nuxt.config") }.joinToString(), result.output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs #!/bin/sh stubs")
    fun `a build script that leaves no server fails with the reason`(@TempDir tmp: File) {
        val frontend = app(tmp, """{"ssr":true,"explicit":true}""")
        File(frontend, ".stub-no-server").writeText("")
        val (runner, _) = withStubs(tmp, "playFrontendSpa")

        val result = runner.buildAndFail()

        assertTrue("pnpm run build did not produce" in result.output && "is the frontend's build script `nuxt build`?" in result.output, result.output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "runs #!/bin/sh stubs")
    fun `a config Nuxt's loader cannot read fails with node's message`(@TempDir tmp: File) {
        val frontend = app(tmp, """{"ssr":true,"explicit":true}""")
        File(frontend, ".stub-node-fail").writeText("")
        val (runner, log) = withStubs(tmp, "playFrontendSpa")

        val result = runner.buildAndFail()

        assertTrue("Could not read frontend/nuxt.config with Nuxt's config loader" in result.output, result.output)
        assertTrue("Cannot find module 'nuxt/package.json'" in result.output, result.output)
        assertFalse(log.readLines().any { it.startsWith("run ") }, "nothing is built when the mode is unknown")
    }

    @Test
    fun `the mode is the last JSON line the check prints`() {
        assertEquals(NuxtSsrMode(ssr = true, explicit = true), parseNuxtSsrMode("[dotenv] loaded .env\n{\"ssr\":true,\"explicit\":true}\n"))
        assertEquals(NuxtSsrMode(ssr = true, explicit = false), parseNuxtSsrMode("{\"ssr\": true, \"explicit\": false}"))
        assertEquals(NuxtSsrMode(ssr = false, explicit = true), parseNuxtSsrMode("{\"ssr\":false,\"explicit\":true}"))
        assertEquals(
            NuxtSsrMode(ssr = true, explicit = true, node = "^20.19.0 || >=22.12.0"),
            parseNuxtSsrMode("{\"ssr\":true,\"explicit\":true,\"node\":\"^20.19.0 || >=22.12.0\"}"),
        )
        assertEquals(NuxtSsrMode(ssr = true, explicit = true), parseNuxtSsrMode("{\"ssr\":true,\"explicit\":true,\"node\":null}"))
        assertNull(parseNuxtSsrMode("nothing useful\n"))
    }

    @Test
    fun `packaging forces in Nuxt's server only when the build left one`(@TempDir tmp: File) {
        File(tmp, "precompiled").mkdirs()
        File(tmp, "public/spa").mkdirs()
        File(tmp, "frontend/.output/public").mkdirs()
        assertEquals(listOf("precompiled", "public/spa"), forcedBuildOutputRoots(tmp).map { it.relativeTo(tmp).path.replace(File.separatorChar, '/') },
            "a static build's frontend/.output duplicates public/spa and stays out")

        File(tmp, "frontend/.output/server").mkdirs()
        File(tmp, "frontend/.output/server/index.mjs").writeText("// server")
        assertEquals(listOf("precompiled", "public/spa", "frontend/.output"), forcedBuildOutputRoots(tmp).map { it.relativeTo(tmp).path.replace(File.separatorChar, '/') })
    }
}
