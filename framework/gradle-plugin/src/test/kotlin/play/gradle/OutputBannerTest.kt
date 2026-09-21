package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * PF-176: playStart and playRestart announced logs/system.out as "output is redirected
 * to" -- the only log location the framework ever named, so operators read it as the
 * application log. It holds console output only; an app's log4j2 file appender writes
 * the application log to a path inside the config that application.log.path names.
 * The banner now says both, resolving the key for the active play id the way the
 * runtime does. bundle-play.sh prints the same lines (see BundleLauncherTest).
 *
 * No JVM needs to come up for this: the banner is printed once the child is spawned,
 * so the framework here is a placeholder jar and the child dies on its unloadable agent.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks a detached JVM from TestKit; unverified on Windows")
class OutputBannerTest {

    @ParameterizedTest
    @ValueSource(strings = ["playStart", "playRestart"])
    fun `banner names system out as console output and the log4j2 config for the play id`(
        task: String, @TempDir tmp: File
    ) {
        val app = writeApp(tmp, "application.log.path=/log4j2.xml\n%prod.application.log.path=/log4j2-prod.xml")

        // The %prod. entry wins under play.id=prod; any other id falls back to the bare key.
        assertEquals("~ application logging follows /log4j2-prod.xml", logConfigLine(app, task, "-PplayId=prod"))
        assertEquals("~ application logging follows /log4j2.xml", logConfigLine(app, task, "-PplayId=staging"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["playStart", "playRestart"])
    fun `banner reports the default config when application log path is unset`(task: String, @TempDir tmp: File) {
        val app = writeApp(tmp, "")

        assertEquals(
            "~ application logging follows the default log4j2 configuration (application.log.path is unset)",
            logConfigLine(app, task)
        )
    }

    /**
     * Run [task] and return its application-logging line, after checking the line before it
     * names logs/system.out as console output.
     */
    private fun logConfigLine(app: File, task: String, vararg args: String): String {
        // The previous run's child may still be alive; a pid file would make playStart refuse.
        File(app, "server.pid").delete()
        val out = TestProject.runner(app, task, *args).build().output
        val lines = out.lines()
        val console = lines.indexOfFirst { it.startsWith("~ console output (stdout/stderr) -> ") }
        assertTrue(console >= 0, "$task printed no console-output line:\n$out")
        // Gradle hands the task a canonicalized project dir (/private/var on macOS).
        assertEquals(
            File(app, "logs/system.out").canonicalPath,
            File(lines[console].substringAfter(" -> ")).canonicalPath
        )
        return lines[console + 1]
    }

    private fun writeApp(tmp: File, confExtra: String): File {
        val fw = File(tmp, "framework-dist")
        // _playValidateFramework only checks the jar exists; empty, it is no loadable agent.
        File(fw, "framework/play-9.9.9-TEST.jar").apply { parentFile.mkdirs(); writeText("") }
        val app = File(tmp, "app").apply { mkdirs() }
        TestProject.write(
            app,
            play1Block = """
                frameworkVersion.set("9.9.9-TEST")
                frameworkPath.set(file("${fw.absolutePath.replace("\\", "\\\\")}"))
            """.trimIndent()
        )
        File(app, "conf").mkdirs()
        File(app, "conf/application.conf").writeText("application.name=testapp\n$confExtra\n")
        return app
    }
}
