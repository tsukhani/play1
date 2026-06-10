package play.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pid-file lifecycle paths that do NOT require a live JVM: playPid and
 * playStatus only read the pid file (resolvePidFile) and inspect process
 * liveness. We drive the no-pid-file and stale/unreadable-pid-file branches with
 * fixtures we write by hand. Actually starting/stopping a process needs a real
 * Play distribution + a forked JVM and is integration-scope (skipped).
 */
class PidLifecycleTest {

    @Test
    fun `playPid reports not running when no pid file exists`(@TempDir tmp: File) {
        TestProject.write(tmp)

        val result = TestProject.runner(tmp, "playPid").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playPid")?.outcome)
        assertTrue(
            result.output.contains("The application is not running"),
            "playPid with no pid file should report not running.\n${result.output}"
        )
    }

    @Test
    fun `playPid prints the pid from an existing pid file`(@TempDir tmp: File) {
        TestProject.write(tmp)
        // Default pid file name is server.pid in the app dir (resolvePidFile).
        File(tmp, "server.pid").writeText("424242\n")

        val result = TestProject.runner(tmp, "playPid").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playPid")?.outcome)
        assertTrue(
            result.output.contains("PID of the running application is 424242"),
            "playPid should echo the pid recorded in server.pid.\n${result.output}"
        )
    }

    @Test
    fun `playPid honors -Ppid-file override`(@TempDir tmp: File) {
        TestProject.write(tmp)
        File(tmp, "custom.pid").writeText("777\n")

        val result = TestProject.runner(tmp, "playPid", "-Ppid-file=custom.pid").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playPid")?.outcome)
        assertTrue(
            result.output.contains("PID of the running application is 777"),
            "playPid should read the pid file named by -Ppid-file.\n${result.output}"
        )
        // Mutation sanity: without the override it would look for server.pid
        // (absent) and report not-running instead.
        val noOverride = TestProject.runner(tmp, "playPid").build()
        assertTrue(
            noOverride.output.contains("The application is not running"),
            "Without -Ppid-file, the default server.pid is absent -> not running.\n${noOverride.output}"
        )
    }

    @Test
    fun `playStatus reports not running when no pid file exists`(@TempDir tmp: File) {
        TestProject.write(tmp)

        val result = TestProject.runner(tmp, "playStatus").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playStatus")?.outcome)
        assertTrue(
            result.output.contains("The application is not running"),
            "playStatus with no pid file should report not running.\n${result.output}"
        )
    }

    @Test
    fun `playStatus detects a stale pid file pointing at a dead process`(@TempDir tmp: File) {
        TestProject.write(tmp)
        // A pid that is essentially guaranteed never to be a live process. The
        // task calls ProcessHandle.of(pid).isPresent and, finding none, reports
        // the file as stale instead of attempting an HTTP /@status fetch.
        val deadPid = "2147480000"
        File(tmp, "server.pid").writeText("$deadPid\n")

        val result = TestProject.runner(tmp, "playStatus").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playStatus")?.outcome)
        assertTrue(
            result.output.contains("Stale pid file") && result.output.contains(deadPid),
            "playStatus should flag a pid file pointing at a non-running pid as stale.\n${result.output}"
        )
    }

    @Test
    fun `playStatus reports an unreadable pid file`(@TempDir tmp: File) {
        TestProject.write(tmp)
        File(tmp, "server.pid").writeText("not-a-number\n")

        val result = TestProject.runner(tmp, "playStatus").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playStatus")?.outcome)
        assertTrue(
            result.output.contains("is unreadable"),
            "playStatus should report a non-numeric pid file as unreadable.\n${result.output}"
        )
    }
}
