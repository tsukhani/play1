package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * PF-202: playStop and playRestart waited for the JVM with `ProcessHandle.onExit()`, which
 * completes at once only for a child of the JVM that waits. For any other process the JDK checks
 * after 300 ms, then 330 ms and so on, and the Gradle process that stops the application started
 * it only when it is the daemon that ran playStart: never under `--no-daemon`, as on CI (PF-197).
 *
 * The process stopped here stands in for the JVM: a bash that traps SIGTERM, started detached
 * (`nohup ... &` from a shell that exits at once), so neither this JVM nor the TestKit daemon is
 * its parent. Its exit is timed by a 1 ms `isAlive` poll from this JVM.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the process in place of the JVM is a bash that traps SIGTERM")
class StopWaitTest {

    /**
     * A bash in place of the JVM, detached from this one. On SIGTERM it exits [termDelay] seconds
     * later, or with null never does, as a JVM still draining. Returns once its trap is set.
     */
    private fun detachedJvm(dir: File, termDelay: String?): ProcessHandle {
        val onTerm = if (termDelay == null) "''" else "'kill \$!; sleep $termDelay; exit 0'"
        val id = System.nanoTime()
        val ready = File(dir, "ready-$id")
        val script = File(dir, "jvm-$id.sh").apply {
            writeText("trap $onTerm TERM\necho ready > '${ready.absolutePath}'\nsleep 120 &\nwait \$!\n")
        }
        val sh = ProcessBuilder("/bin/sh", "-c", "nohup /bin/bash \"\$0\" >/dev/null 2>&1 & echo \$!", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val pid = sh.inputStream.bufferedReader().readLine().trim().toLong()
        sh.waitFor()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready.exists()) {
            check(System.nanoTime() < deadline) { "the process in place of the JVM never set its trap" }
            Thread.sleep(5)
        }
        return ProcessHandle.of(pid).orElseThrow()
    }

    /** A child of this JVM in place of the JVM, as one the stopping daemon itself started. */
    private fun childJvm(dir: File, termDelay: String): Pair<Process, ProcessHandle> {
        val ready = File(dir, "ready-${System.nanoTime()}")
        val p = ProcessBuilder("/bin/bash", "-c",
            "trap 'kill \$!; sleep $termDelay; exit 0' TERM; echo ready > '${ready.absolutePath}'; sleep 120 & wait \$!")
            .start()
        while (!ready.exists()) Thread.sleep(5)
        return p to p.toHandle()
    }

    /** When [handle] stops being alive, by a 1 ms poll; call the result to get it. */
    private fun goneAt(handle: ProcessHandle): () -> Long {
        var at = 0L
        val poll = Thread.ofPlatform().daemon().start {
            while (handle.isAlive) Thread.sleep(1)
            at = System.nanoTime()
        }
        return { poll.join(10_000); at }
    }

    private fun ProcessHandle.killTree() {
        descendants().forEach { it.destroyForcibly() }
        destroyForcibly()
    }

    private fun median(ms: List<Double>) = ms.sorted()[ms.size / 2]

    @Test
    fun `awaitExit notices the exit of a process it did not start within about 20 ms`(@TempDir tmp: File) {
        // exits at ten points spread over three checks: onExit() alone took 300 ms here
        val lags = (0 until 10).map { i ->
            val jvm = detachedJvm(tmp, "0.%03d".format(7 * i))
            try {
                val gone = goneAt(jvm)
                jvm.destroy()
                assertTrue(awaitExit(jvm, 10_000))
                val returned = System.nanoTime()
                (returned - gone()) / 1_000_000.0
            } finally {
                jvm.killTree()
            }
        }
        assertTrue(median(lags) < 30.0, "ms from the exit to awaitExit's return: $lags")
    }

    @Test
    fun `awaitExit returns at the exit of a child as onExit does`(@TempDir tmp: File) {
        val lags = (0 until 8).map { i ->
            val (p, jvm) = childJvm(tmp, "0.%03d".format(7 * i))
            try {
                var exited = 0L
                // the JDK reaps its own child at once: waitFor() returns at the exit
                val watcher = Thread.ofPlatform().start { p.waitFor(); exited = System.nanoTime() }
                jvm.destroy()
                assertTrue(awaitExit(jvm, 10_000))
                val returned = System.nanoTime()
                watcher.join(10_000)
                (returned - exited) / 1_000_000.0
            } finally {
                jvm.killTree()
            }
        }
        // no 20 ms poll in the way: as quick as the onExit() alone the tasks used before
        assertTrue(median(lags) < 5.0, "ms from the exit to awaitExit's return: $lags")
    }

    @Test
    fun `awaitExit gives up after its timeout and leaves the process running`(@TempDir tmp: File) {
        val jvm = detachedJvm(tmp, termDelay = null)
        try {
            jvm.destroy()
            val started = System.nanoTime()
            assertFalse(awaitExit(jvm, 300))
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(ms in 300..1_000, "awaitExit waited $ms ms")
            assertTrue(jvm.isAlive, "awaitExit must not kill what it waits for")
        } finally {
            jvm.killTree()
        }
    }

    /** Gradle's output as it comes, with the moment [marker] first arrived. */
    private class LineTimer(private val marker: String) : Writer() {
        private val text = StringBuilder()
        @Volatile var seenAt = 0L

        override fun write(cbuf: CharArray, off: Int, len: Int) = synchronized(text) {
            text.append(cbuf, off, len)
            if (seenAt == 0L && text.contains(marker)) seenAt = System.nanoTime()
        }

        override fun flush() {}
        override fun close() {}
        override fun toString() = synchronized(text) { text.toString() }
    }

    /**
     * The task waits with [awaitExit], whose 20 ms the tests above pin. What reaches this JVM
     * also crosses TestKit's forwarding from its daemon, about 10 ms more, so this bound only
     * proves the task no longer waits out onExit()'s first 300 ms.
     */
    @Test
    fun `playStop says a JVM Gradle did not start is stopped without waiting out onExit's 300 ms`(@TempDir tmp: File) {
        TestProject.write(tmp)
        TestProject.runner(tmp, "help", "--quiet").build() // the TestKit daemon, up before any timing

        val lags = (0 until 8).map { i ->
            val jvm = detachedJvm(tmp, "0.%03d".format(9 * i))
            try {
                File(tmp, "server.pid").writeText("${jvm.pid()}")
                val gone = goneAt(jvm)
                val out = LineTimer("is stopped")
                TestProject.runner(tmp, "playStop").forwardStdOutput(out).build()
                assertTrue(out.seenAt != 0L, "playStop never said the JVM was stopped:\n$out")
                assertFalse(File(tmp, "server.pid").exists(), "the pid file outlived the JVM:\n$out")
                (out.seenAt - gone()) / 1_000_000.0
            } finally {
                jvm.killTree()
            }
        }
        assertTrue(median(lags) < 100.0, "ms from the exit to \"is stopped\": $lags")
    }

    @Test
    fun `playStop leaves a JVM still draining after 35 s running with its pid file`(@TempDir tmp: File) {
        TestProject.write(tmp)
        val jvm = detachedJvm(tmp, termDelay = null)
        try {
            File(tmp, "server.pid").writeText("${jvm.pid()}")
            val started = System.nanoTime()
            val result = TestProject.runner(tmp, "playStop").build()
            val seconds = (System.nanoTime() - started) / 1e9

            assertTrue(result.output.contains("still draining after 35s"), result.output)
            assertTrue(seconds >= 35.0, "playStop waited $seconds s")
            assertTrue(jvm.isAlive, "playStop must not kill a JVM that is still draining")
            assertEquals("${jvm.pid()}", File(tmp, "server.pid").readText(), "status and pid keep tracking it")
        } finally {
            jvm.killTree()
        }
    }
}
