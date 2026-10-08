package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The JVM flags the Gradle launch paths lift out of application.conf (confJvmArgs, shared by
 * playRun, playStart and playAutotest). BundleLauncherTest holds the bundle launcher's bash
 * port to the same output; this pins what that output is.
 */
class ConfJvmArgsTest {

    private fun flags(dir: File, conf: String, playId: String = "prod"): List<String> {
        File(dir, "conf").mkdirs()
        File(dir, "conf/application.conf").writeText(conf)
        return confJvmArgs(dir, playId)
    }

    @Test
    fun `a blank value is unset and does not swallow the next line`(@TempDir tmp: File) {
        // `jvm.memory=` used to yield "javaagent.path=bin/agent.jar" as a JVM flag: the
        // pattern's \s* ran across the line break, and the JVM then refused to start.
        assertEquals(
            listOf("-javaagent:bin/agent.jar"),
            flags(tmp, "jvm.memory=\njavaagent.path=bin/agent.jar\n")
        )
        assertEquals(listOf("-Xmx1g"), flags(tmp, "agentlib=   \njvm.memory=-Xmx1g\njavaagent.path=\n"))
        assertEquals(emptyList<String>(), flags(tmp, "jmx.port=\njmx.hostname=127.0.0.1\n"))
    }

    @Test
    fun `jmx port and hostname alone start an agent that demands a login and TLS`(@TempDir tmp: File) {
        // PF-183: both were hard-coded off, so the two keys meant an open agent.
        assertEquals(
            listOf(
                "-Dcom.sun.management.jmxremote",
                "-Dcom.sun.management.jmxremote.port=9010",
                "-Dcom.sun.management.jmxremote.ssl=true",
                "-Dcom.sun.management.jmxremote.authenticate=true",
                "-Dcom.sun.management.jmxremote.local.only=false",
                "-Dcom.sun.management.jmxremote.host=10.0.0.5",
                "-Djava.rmi.server.hostname=10.0.0.5",
                "-Dcom.sun.management.jmxremote.registry.ssl=true"
            ),
            flags(tmp, "jmx.port=9010\njmx.hostname=10.0.0.5\n")
        )
    }

    @Test
    fun `only the literal word false turns jmx authentication or TLS off`(@TempDir tmp: File) {
        fun switches(conf: String) = flags(tmp, "jmx.port=9010\njmx.hostname=10.0.0.5\n$conf")
            .filter { it.contains(".ssl=") || it.contains(".authenticate=") }
            .map { it.removePrefix("-Dcom.sun.management.jmxremote.") }

        assertEquals(
            listOf("ssl=false", "authenticate=false", "registry.ssl=false"),
            switches("jmx.authenticate=false\njmx.ssl=FALSE\n")
        )
        // The JDK reads anything but "true" as off; passed through, a typo would open the agent.
        assertEquals(
            listOf("ssl=true", "authenticate=true", "registry.ssl=true"),
            switches("jmx.authenticate=ture\njmx.ssl=no\n")
        )
        // The play id's own entry decides, as for every other key.
        assertEquals(
            listOf("ssl=true", "authenticate=false", "registry.ssl=true"),
            switches("jmx.authenticate=true\n%prod.jmx.authenticate=false\n")
        )
    }
}
