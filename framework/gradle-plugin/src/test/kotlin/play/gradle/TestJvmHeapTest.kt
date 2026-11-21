package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * PF-230: the heap Gradle's test task names for its JVMs. Gradle passes -Xmx512m to a test JVM
 * whose task names none, after every other option, so the task names the heap the JVM would
 * size for jvm.memory's flags itself, as playAutotest's server JVM, which Gradle does not start,
 * sizes it.
 */
class TestJvmHeapTest {

    private val gib = 1L shl 30

    @Test
    fun `an -Xmx or -XX MaxHeapSize is the heap, the last one winning`() {
        assertEquals("3g", testJvmMaxHeap(listOf("-Xms1g", "-Xmx3g"), 48 * gib))
        assertEquals("2g", testJvmMaxHeap(listOf("-XX:MaxHeapSize=3g", "-Xmx2g"), 48 * gib))
        assertEquals("3g", testJvmMaxHeap(listOf("-Xmx2g", "-XX:MaxHeapSize=3g"), 48 * gib))
        // a percentage of the memory is no limit beside it
        assertEquals("1g", testJvmMaxHeap(listOf("-XX:MaxRAMPercentage=10", "-Xmx1g"), 48 * gib))
    }

    @Test
    fun `without a heap flag the heap is a quarter of the memory, of 128 GB at most`() {
        assertEquals("12288m", testJvmMaxHeap(emptyList(), 48 * gib))
        assertEquals("12288m", testJvmMaxHeap(listOf("-Xss1m", "--add-modules=jdk.incubator.vector"), 48 * gib))
        assertEquals("32768m", testJvmMaxHeap(emptyList(), 256 * gib))
    }

    @Test
    fun `MaxRAMPercentage and MaxRAM size it as the JVM does`() {
        assertEquals("4915m", testJvmMaxHeap(listOf("-XX:MaxRAMPercentage=10"), 48 * gib))
        // a limit of the application's own: all of the memory counts, not 128 GB of it
        assertEquals("196608m", testJvmMaxHeap(listOf("-XX:MaxRAMPercentage=75"), 256 * gib))
        assertEquals("1024m", testJvmMaxHeap(listOf("-XX:MaxRAM=4g"), 48 * gib))
        assertEquals("2048m", testJvmMaxHeap(listOf("-XX:MaxRAM=4g", "-XX:MaxRAMPercentage=50"), 48 * gib))
        assertEquals("1024m", testJvmMaxHeap(listOf("-XX:MaxRAM=4294967296"), 48 * gib))
        assertEquals("15360m", testJvmMaxHeap(listOf("-XX:MaxRAMPercentage=10", "-XX:MaxRAMPercentage=30"), 50 * gib))
    }

    @Test
    fun `the heap is the one this machine's JVM sizes for the same flags`() {
        val memory = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
        for (flags in listOf(
            emptyList(),
            listOf("-XX:MaxRAMPercentage=10"),
            listOf("-XX:MaxRAM=2g"),
            listOf("-XX:MaxRAM=6g", "-XX:MaxRAMPercentage=60"),
            listOf("-Xmx768m"),
        )) {
            val named = jvmBytes(testJvmMaxHeap(flags, memory))
            val sized = maxHeapSizeOf(flags)
            // The JVM aligns the value up, by a few MB
            assertTrue(sized - named in 0L..(64L shl 20), "$flags: the task names $named bytes, the JVM sizes $sized")
        }
    }

    private fun jvmBytes(size: String): Long {
        val unit = size.last().lowercaseChar()
        val shift = when (unit) { 'k' -> 10; 'm' -> 20; 'g' -> 30; else -> 0 }
        return (if (unit.isDigit()) size else size.dropLast(1)).toLong() shl shift
    }

    private fun maxHeapSizeOf(flags: List<String>): Long {
        val java = File(System.getProperty("java.home"), if (File.separatorChar == '\\') "bin/java.exe" else "bin/java").path
        val process = ProcessBuilder(listOf(java) + flags + listOf("-XX:+PrintFlagsFinal", "-version"))
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, out)
        return Regex("""\sMaxHeapSize\s+=\s+(\d+)""").find(out)!!.groupValues[1].toLong()
    }
}
