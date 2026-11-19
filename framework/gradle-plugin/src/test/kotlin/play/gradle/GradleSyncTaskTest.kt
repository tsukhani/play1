package play.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PF-216: an application's Gradle wrapper follows the framework's. Every build warns while
 * gradle/wrapper/gradle-wrapper.properties names another distribution or checksum, and
 * playGradleSync moves the wrapper to the framework's. TestKit runs the Gradle of this build,
 * never the test project's wrapper, so a test project can name any distribution it likes.
 */
class GradleSyncTaskTest {

    private val repoWrapper = File("../../gradle/wrapper/gradle-wrapper.properties").readText()
    private val framework = wrapperDistributionOf(repoWrapper)
    private val urlLine = repoWrapper.lines().single { it.startsWith("distributionUrl=") }
    private val sumLine = repoWrapper.lines().single { it.startsWith("distributionSha256Sum=") }

    private fun wrapper(dir: File, properties: String) =
        File(dir, WRAPPER_PROPERTIES).apply { parentFile.mkdirs(); writeText(properties) }

    private fun warnings(result: BuildResult) =
        result.output.lines().filter { it.startsWith("~ WARNING: $WRAPPER_PROPERTIES names") }

    @Test
    fun `a wrapper naming another distribution gets one warning and the build goes on`(@TempDir tmp: File) {
        TestProject.write(tmp)
        wrapper(tmp, "distributionSha256Sum=old-sum\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-9.0.0-bin.zip\n")

        val result = TestProject.runner(tmp, "help").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome, result.output)
        val warning = warnings(result).single()
        assertTrue("names https://services.gradle.org/distributions/gradle-9.0.0-bin.zip (distributionSha256Sum old-sum)" in warning, warning)
        assertTrue("builds with ${framework.url} (distributionSha256Sum ${framework.sha256})" in warning, warning)
        assertTrue("play gradle-sync" in warning, warning)
    }

    @Test
    fun `the framework's distribution with another checksum or none gets one warning`(@TempDir tmp: File) {
        // An application's case: a Renovate bump moved distributionUrl and kept the old checksum.
        for ((name, sum) in listOf("stale" to "distributionSha256Sum=old-sum\n", "none" to "")) {
            val app = File(tmp, name).apply { mkdirs() }
            TestProject.write(app)
            wrapper(app, "distributionBase=GRADLE_USER_HOME\n$sum$urlLine\n")

            val result = TestProject.runner(app, "help").build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome, result.output)
            assertEquals(1, warnings(result).size, result.output)
        }
    }

    @Test
    fun `the framework's own wrapper, or none, gets no warning`(@TempDir tmp: File) {
        val synced = File(tmp, "synced").also { it.mkdirs(); TestProject.write(it); wrapper(it, repoWrapper) }
        val without = File(tmp, "without").also { it.mkdirs(); TestProject.write(it) }

        for (app in listOf(synced, without)) {
            val result = TestProject.runner(app, "help").build()
            assertEquals(emptyList<String>(), warnings(result), result.output)
        }
    }

    @Test
    // PF-219: in the serial task, never beside the parallel forks. Under heavy file churn, which
    // the forks produce, the second build sometimes reused the configuration cache although the
    // file had changed, with file system watching on or off: about 3 of 12 parallel suite runs on
    // 2026-11-18, and none of 11 runs alone.
    @Tag("timing")
    fun `under the configuration cache the build that changes the wrapper warns`(@TempDir tmp: File) {
        TestProject.write(tmp)
        val file = wrapper(tmp, repoWrapper)
        val first = TestProject.runner(tmp, "help", "--configuration-cache").build()
        assertEquals(emptyList<String>(), warnings(first), first.output)

        file.writeText(repoWrapper.replace(sumLine, "distributionSha256Sum=old-sum"))
        val second = TestProject.runner(tmp, "help", "--configuration-cache").build()

        assertEquals(1, warnings(second).size, "the changed wrapper reconfigures and warns\n${second.output}")
        assertTrue("Configuration cache entry stored" in second.output, "no configuration cache problem\n${second.output}")
    }

    @Test
    fun `the sync moves the distribution and checksum, keeps the other properties and writes the framework's jar and scripts`(@TempDir tmp: File) {
        TestProject.write(tmp)
        val file = wrapper(
            tmp,
            "distributionBase=GRADLE_USER_HOME\n# a comment the sync keeps\ndistributionSha256Sum=old-sum\n" +
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.0.0-bin.zip\nnetworkTimeout=60000\n",
        )
        File(tmp, "gradlew").writeText("old script\n")
        File(tmp, "gradlew.bat").writeText("old script\r\n")
        File(tmp, "gradle/wrapper/gradle-wrapper.jar").writeText("old jar")

        val result = TestProject.runner(tmp, "playGradleSync").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":playGradleSync")?.outcome, result.output)
        assertEquals(
            "distributionBase=GRADLE_USER_HOME\n# a comment the sync keeps\n$sumLine\n$urlLine\nnetworkTimeout=60000\n",
            file.readText(),
        )
        assertTheFrameworksJarAndScripts(tmp)
        assertTrue("Commit gradle/wrapper/, gradlew and gradlew.bat together" in result.output, result.output)

        val after = TestProject.runner(tmp, "help").build()
        assertEquals(emptyList<String>(), warnings(after), "the synced wrapper gets no warning\n${after.output}")
    }

    @Test
    fun `the sync adds a missing checksum`(@TempDir tmp: File) {
        TestProject.write(tmp)
        val file = wrapper(tmp, "distributionBase=GRADLE_USER_HOME\n$urlLine\nzipStorePath=wrapper/dists\n")

        TestProject.runner(tmp, "playGradleSync").build()

        assertEquals("distributionBase=GRADLE_USER_HOME\n$sumLine\n$urlLine\nzipStorePath=wrapper/dists\n", file.readText())
    }

    @Test
    fun `the sync writes the whole wrapper into an application without one`(@TempDir tmp: File) {
        TestProject.write(tmp)

        TestProject.runner(tmp, "playGradleSync").build()

        assertEquals(repoWrapper, File(tmp, WRAPPER_PROPERTIES).readText())
        assertTheFrameworksJarAndScripts(tmp)
    }

    private fun assertTheFrameworksJarAndScripts(app: File) {
        for (path in listOf("gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat")) {
            assertArrayEquals(File("../../$path").readBytes(), File(app, path).readBytes(), path)
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            assertTrue(File(app, "gradlew").canExecute(), "gradlew is executable")
        }
    }
}
