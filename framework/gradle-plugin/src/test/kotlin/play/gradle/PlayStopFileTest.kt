package play.gradle

import org.gradle.api.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * PF-243: on Windows `ProcessHandle.destroy()` terminates the JVM before any shutdown hook runs,
 * so playStop and playRestart stop it through the stop file beside its pid file, which the JVM
 * watches (`play.server.StopFile`), and terminate it only after the stop wait. Off Windows they
 * still send SIGTERM.
 *
 * The process in place of the JVM is a real JVM, a single-file Java program that watches the
 * stop file as the framework does and writes a marker from a shutdown hook, so each case runs on
 * every OS: on Windows it is the native java.exe of a real stop. [stopPlay] is called with the
 * Windows branch chosen, whatever the OS. `integration.H2ShutdownTest` and
 * `integration.ShutdownLoggingTest` stop real Play JVMs through the file.
 */
class PlayStopFileTest {

    /** A JVM in place of Play's, and the files it watches and writes. */
    private class StandIn(val process: Process, val stopFile: File, val marker: File) : AutoCloseable {
        val hookRan get() = marker.isFile
        override fun close() {
            process.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Start a JVM that, once [stopFile] appears and [mode] is `exit`, removes it and exits 0, as
     * Play's watch does; `take` removes it and goes on running, as a JVM whose shutdown hangs;
     * `ignore` never looks, as a JVM started by a framework without the watch. Its shutdown hook
     * writes [StandIn.marker]. Returns once it is watching.
     */
    private fun standIn(dir: File, mode: String, stopFile: File = File(dir, "server.pid.stop")): StandIn {
        val source = File(dir, "StandIn.java").apply {
            writeText(
                """
                import java.nio.file.*;
                public class StandIn {
                    public static void main(String[] a) throws Exception {
                        Path stop = Path.of(a[0]);
                        String mode = a[1];
                        Path marker = Path.of(a[2]);
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try { Files.writeString(marker, "hook ran"); } catch (Exception e) { }
                        }));
                        Files.writeString(Path.of(a[3]), "ready");
                        while (true) {
                            if (!mode.equals("ignore") && Files.exists(stop)) {
                                Files.deleteIfExists(stop);
                                if (mode.equals("exit")) System.exit(0);
                                mode = "ignore";
                            }
                            Thread.sleep(20);
                        }
                    }
                }
                """.trimIndent()
            )
        }
        val marker = File(dir, "hook-$mode")
        val ready = File(dir, "ready-$mode")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(java, source.absolutePath, stopFile.absolutePath, mode, marker.absolutePath, ready.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(File(dir, "standin-$mode.out"))
            .start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!ready.isFile) {
            check(process.isAlive) { "the process in place of the JVM ended: ${File(dir, "standin-$mode.out").readText()}" }
            check(System.nanoTime() < deadline) { "the process in place of the JVM never started" }
            Thread.sleep(20)
        }
        return StandIn(process, stopFile, marker)
    }

    /** A logger that keeps what is logged at lifecycle level, which is all [stopPlay] logs. */
    private class Lines {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val logger = Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            if (method.name == "lifecycle" && args?.size == 1) lines += args[0] as String
            if (method.returnType == java.lang.Boolean.TYPE) false else null
        } as Logger
    }

    @Test
    fun `a stop through the stop file runs the JVM's shutdown hook and removes the file`(@TempDir tmp: File) {
        standIn(tmp, "exit").use { jvm ->
            val log = Lines()
            val started = System.nanoTime()

            assertEquals(PlayStop.EXITED, stopPlay(jvm.process.toHandle(), jvm.stopFile, throughFile = true, log.logger, waitSeconds = 30))

            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue(seconds < 10, "the stop took $seconds s")
            assertTrue(jvm.hookRan, "the JVM's shutdown hook did not run")
            assertFalse(jvm.stopFile.exists(), "the stop file outlived the stop")
            assertEquals(emptyList<String>(), log.lines, "a graceful stop says nothing of its own")
        }
    }

    @Test
    fun `a JVM that never takes the stop file is terminated after the wait, and the line says so`(@TempDir tmp: File) {
        standIn(tmp, "ignore").use { jvm ->
            val log = Lines()
            val started = System.nanoTime()

            assertEquals(PlayStop.TERMINATED, stopPlay(jvm.process.toHandle(), jvm.stopFile, throughFile = true, log.logger, waitSeconds = 2))

            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue(seconds >= 2.0, "terminated after $seconds s, before the stop wait was out")
            assertFalse(jvm.process.isAlive)
            assertFalse(jvm.hookRan, "a forced terminate runs no shutdown hook, on any OS")
            assertFalse(jvm.stopFile.exists(), "the stop file outlived the stop")
            assertEquals(1, log.lines.size, "${log.lines}")
            assertTrue("did not take its stop file in 2s" in log.lines[0], log.lines[0])
            assertTrue("terminated it, so no shutdown hook ran" in log.lines[0], log.lines[0])
            assertFalse(log.lines.any { "SIGTERM" in it }, "no SIGTERM was sent: ${log.lines}")
        }
    }

    @Test
    fun `a JVM still stopping after it took the stop file is terminated after the wait, and the line says so`(@TempDir tmp: File) {
        standIn(tmp, "take").use { jvm ->
            val log = Lines()

            assertEquals(PlayStop.TERMINATED, stopPlay(jvm.process.toHandle(), jvm.stopFile, throughFile = true, log.logger, waitSeconds = 2))

            assertFalse(jvm.process.isAlive)
            assertEquals(1, log.lines.size, "${log.lines}")
            assertTrue("was still stopping 2s after it took its stop file" in log.lines[0], log.lines[0])
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "asserts the SIGTERM a stop sends off Windows")
    fun `off Windows a stop is still a SIGTERM and writes no stop file`(@TempDir tmp: File) {
        standIn(tmp, "ignore").use { jvm ->
            val log = Lines()

            assertEquals(PlayStop.EXITED, stopPlay(jvm.process.toHandle(), jvm.stopFile, throughFile = false, log.logger, waitSeconds = 30))

            assertTrue(jvm.hookRan, "a SIGTERM runs the JVM's shutdown hook")
            assertFalse(jvm.stopFile.exists())
            assertEquals(emptyList<String>(), log.lines)
        }
    }

    @Test
    fun `only Windows stops through the file`() {
        for (os in listOf("Windows 11", "Windows Server 2022", "Windows 10")) assertTrue(stopsThroughFile(os), os)
        for (os in listOf("Mac OS X", "Linux", "FreeBSD", "SunOS")) assertFalse(stopsThroughFile(os), os)
    }

    @Test
    fun `the stop file is the pid file's name and stop, beside it`(@TempDir tmp: File) {
        assertEquals(File(tmp, "server.pid.stop"), playStopFile(File(tmp, "server.pid")))
        assertEquals(File(tmp, "run/app.lock.stop"), playStopFile(File(tmp, "run/app.lock")))
    }

    @Test
    fun `a start names the stop file to the JVM only where a stop writes it, and removes one left behind there`(@TempDir tmp: File) {
        val pidFile = File(tmp, "server.pid")
        val left = File(tmp, "server.pid.stop").apply { writeText("") }

        assertEquals(left, startStopFile(pidFile, throughFile = true))
        assertFalse(left.exists(), "a stop file left behind would stop the new JVM at once")

        // Off Windows none: a stop file can be made by anyone who may create a file in the pid
        // file's folder, a SIGTERM only by the JVM's own user or root. One there is not watched.
        left.writeText("")
        assertEquals(null, startStopFile(pidFile, throughFile = false))
        assertTrue(left.exists())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `on Windows playStop stops the JVM through its stop file and its shutdown hook runs`(@TempDir tmp: File) {
        TestProject.write(tmp)
        standIn(tmp, "exit").use { jvm ->
            File(tmp, "server.pid").writeText("${jvm.process.pid()}")

            val result = TestProject.runner(tmp, "playStop").build()

            assertTrue("is stopped" in result.output, result.output)
            assertFalse("SIGTERM" in result.output, result.output)
            assertFalse("forced terminate" in result.output, result.output)
            assertTrue(jvm.process.waitFor(10, TimeUnit.SECONDS))
            assertTrue(jvm.hookRan, "the JVM's shutdown hook did not run:\n${result.output}")
            assertFalse(File(tmp, "server.pid").exists())
            assertFalse(jvm.stopFile.exists())
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `on Windows playStop terminates a JVM that does not take its stop file after the wait`(@TempDir tmp: File) {
        TestProject.write(tmp)
        standIn(tmp, "ignore").use { jvm ->
            File(tmp, "server.pid").writeText("${jvm.process.pid()}")

            val result = TestProject.runner(tmp, "playStop")
                .withEnvironment(System.getenv() + ("PLAY_TEST_STOP_WAIT_SECONDS" to "2"))
                .build()

            assertTrue("did not take its stop file in 2s" in result.output, result.output)
            assertTrue("is stopped, by a forced terminate" in result.output, result.output)
            assertFalse(jvm.process.isAlive)
            assertFalse(File(tmp, "server.pid").exists())
            assertFalse(jvm.stopFile.exists())
        }
    }
}
