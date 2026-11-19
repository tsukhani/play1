package play.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.Properties

/**
 * PF-216: an application's Gradle wrapper follows the framework's, which is the fork's own
 * gradle/wrapper/gradle-wrapper.properties. These pin the arithmetic; GradleSyncTaskTest runs the
 * warning and the task in real builds.
 */
class GradleWrapperPinTest {

    private val framework = WrapperDistribution("https://example.org/gradle-2.0-bin.zip", "new-sum")

    private val frameworkProperties =
        "distributionBase=GRADLE_USER_HOME\ndistributionSha256Sum=new-sum\ndistributionUrl=https\\://example.org/gradle-2.0-bin.zip\n"

    @Test
    fun `the plugin reports the distribution and checksum of the repo's own wrapper`() {
        // The plugin reads the copy its jar carries, which processResources takes from the repo.
        // Reading the repo's file here fails the suite when that copy goes missing or stale.
        val repo = Properties().apply { File("../../gradle/wrapper/gradle-wrapper.properties").reader().use { load(it) } }
        val reported = frameworkGradleDistribution()

        assertNotNull(repo.getProperty("distributionSha256Sum"), "the fork's own wrapper names a checksum")
        assertEquals(WrapperDistribution(repo.getProperty("distributionUrl"), repo.getProperty("distributionSha256Sum")), reported)
    }

    @Test
    fun `the plugin carries the repo's wrapper jar and scripts`() {
        // What playGradleSync writes is what playNewApp copies into a new application.
        for ((resource, repoPath) in listOf(
            "gradle-wrapper.jar" to "gradle/wrapper/gradle-wrapper.jar",
            "gradlew" to "gradlew",
            "gradlew.bat" to "gradlew.bat",
        )) {
            val carried = javaClass.getResource("/play/gradle/wrapper/$resource")
            assertNotNull(carried, "the jar carries $resource")
            assertArrayEquals(File("../../$repoPath").readBytes(), carried!!.readBytes(), resource)
        }
    }

    @Test
    fun `the framework's own wrapper gets no warning`() {
        assertNull(gradleWrapperWarning(framework, framework))
    }

    @Test
    fun `the warning names both distributions and checksums, and the fix`() {
        val warning = gradleWrapperWarning(WrapperDistribution("https://example.org/gradle-1.0-bin.zip", "old-sum"), framework)!!

        assertTrue("names https://example.org/gradle-1.0-bin.zip (distributionSha256Sum old-sum)" in warning, warning)
        assertTrue("builds with https://example.org/gradle-2.0-bin.zip (distributionSha256Sum new-sum)" in warning, warning)
        assertTrue("play gradle-sync" in warning, warning)
    }

    @Test
    fun `the framework's distribution with another checksum or none is warned about`() {
        // An application's case: Renovate moved distributionUrl and left the old checksum.
        assertNotNull(gradleWrapperWarning(WrapperDistribution(framework.url, "old-sum"), framework))
        val none = gradleWrapperWarning(WrapperDistribution(framework.url, null), framework)!!
        assertTrue("(no distributionSha256Sum)" in none, none)
    }

    @Test
    fun `the distribution and checksum are rewritten in place and nothing else moves`() {
        val before = "# kept\ndistributionBase=GRADLE_USER_HOME\ndistributionSha256Sum=old-sum\n" +
            "distributionUrl=https\\://example.org/gradle-1.0-bin.zip\nnetworkTimeout=60000\ncustom.key = kept too\n"

        assertEquals(
            "# kept\ndistributionBase=GRADLE_USER_HOME\ndistributionSha256Sum=new-sum\n" +
                "distributionUrl=https\\://example.org/gradle-2.0-bin.zip\nnetworkTimeout=60000\ncustom.key = kept too\n",
            withFrameworkDistribution(before, frameworkProperties),
        )
    }

    @Test
    fun `a missing checksum goes in above distributionUrl`() {
        assertEquals(
            "networkTimeout=10000\ndistributionSha256Sum=new-sum\ndistributionUrl=https\\://example.org/gradle-2.0-bin.zip\nzipStorePath=wrapper/dists\n",
            withFrameworkDistribution(
                "networkTimeout=10000\ndistributionUrl=https\\://example.org/gradle-1.0-bin.zip\nzipStorePath=wrapper/dists\n",
                frameworkProperties,
            ),
        )
    }

    @Test
    fun `a CRLF file keeps CRLF, and a file naming no distribution gets both lines at its end`() {
        assertEquals(
            "networkTimeout=10000\r\ndistributionSha256Sum=new-sum\r\ndistributionUrl=https\\://example.org/gradle-2.0-bin.zip\r\n",
            withFrameworkDistribution("networkTimeout=10000\r\n", frameworkProperties),
        )
        // Without a final line terminator, the lines still go on lines of their own.
        assertEquals(
            "networkTimeout=10000\ndistributionSha256Sum=new-sum\ndistributionUrl=https\\://example.org/gradle-2.0-bin.zip\n",
            withFrameworkDistribution("networkTimeout=10000", frameworkProperties),
        )
    }

    @Test
    fun `the framework's own file is left as it is`() {
        assertEquals(frameworkProperties, withFrameworkDistribution(frameworkProperties, frameworkProperties))
    }

    @Test
    fun `a value continued onto the next line fails rather than being corrupted`() {
        val continued = "distributionSha256Sum=old-sum\ndistributionUrl=https\\://example.org/\\\n    gradle-1.0-bin.zip\n"

        val e = assertThrows<GradleException> { withFrameworkDistribution(continued, frameworkProperties) }
        assertTrue("distributionUrl=https\\://example.org/gradle-2.0-bin.zip" in e.message!!, "the message gives the lines to set\n${e.message}")
    }
}
