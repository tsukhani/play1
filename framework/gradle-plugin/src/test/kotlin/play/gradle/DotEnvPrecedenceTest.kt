package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.ServerSocket
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/**
 * PF-184: certs/.env only supplies the variables the host environment leaves undefined. A
 * variable the host sets -- even to the empty string -- reaches the forked JVM unchanged.
 * registerPlayJvmTask (playRun) applied every certs/.env entry over the inherited
 * environment, so a value exported at deploy time was silently replaced by the file, while
 * spawnPlay (playStart) and playAutotest let the host win.
 *
 * The plugin builds that environment in three independent places, so passing one says
 * nothing about the others. Each task runs for real against a fake framework whose
 * play.server.Server prints the value it was handed.
 */
// Same reservation as JulBridgeArgsTest: the forked JVMs and the detached playStart child
// are unverified on Windows; the environment code under test is OS-neutral.
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks detached JVMs from TestKit; unverified on Windows")
class DotEnvPrecedenceTest {

    private val fwVersion = "9.9.9-TEST"
    private val probe = "PF184_PROBE"

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playStart", "playAutotest"])
    fun `a host variable is not overridden by certs dot env`(task: String, @TempDir tmp: File) {
        assertEquals("[from-host]", probeSeenBy(writeApp(tmp), task, hostValue = "from-host"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playStart", "playAutotest"])
    fun `a host variable set to the empty string is not overridden by certs dot env`(task: String, @TempDir tmp: File) {
        assertEquals("[]", probeSeenBy(writeApp(tmp), task, hostValue = ""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playStart", "playAutotest"])
    fun `certs dot env supplies a variable the host does not define`(task: String, @TempDir tmp: File) {
        assertEquals("[from-dotenv]", probeSeenBy(writeApp(tmp), task, hostValue = null))
    }

    /**
     * Run [task] with the probe set to [hostValue] in the build's environment (undefined when
     * null) and return what its forked JVM saw: the value in brackets, or `<unset>`.
     */
    private fun probeSeenBy(app: File, task: String, hostValue: String?): String {
        // withEnvironment replaces the build's whole environment, so start from this one.
        val env = System.getenv().toMutableMap()
        if (hostValue == null) env.remove(probe) else env[probe] = hostValue
        // An explicit free port: playAutotest POSTs /@kill to its port before and after the
        // run, which must never reach a real Play server on this machine's default 9000.
        val port = ServerSocket(0).use { it.localPort }
        val result = TestProject.runner(app, task, "-PhttpPort=$port").withEnvironment(env).build()
        // playRun forwards the JVM's stdout into the build output; the other two redirect it
        // to logs/system.out, and playStart returns before its detached child has written.
        val out = if (task == "playRun") result.output else awaitSystemOut(app)
        return Regex("$probe=(\\[.*]|<unset>)").find(out)?.groupValues?.get(1)
            ?: fail("$task's JVM never reported $probe:\n$out")
    }

    private fun awaitSystemOut(app: File): String {
        val systemOut = File(app, "logs/system.out")
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (systemOut.isFile && systemOut.readText().contains("$probe=")) return systemOut.readText()
            Thread.sleep(100)
        }
        return if (systemOut.isFile) systemOut.readText() else ""
    }

    private fun writeApp(tmp: File): File {
        val fw = fakeFramework(File(tmp, "framework-dist"))
        val app = File(tmp, "app").apply { mkdirs() }
        TestProject.write(
            app,
            play1Block = """
                frameworkVersion.set("$fwVersion")
                frameworkPath.set(file("${fw.absolutePath.replace("\\", "\\\\")}"))
            """.trimIndent()
        )
        File(app, "conf").mkdirs()
        // playAutotest refuses to start without both files.
        File(app, "conf/routes").writeText("")
        File(app, "conf/application.conf").writeText("application.name=testapp\n")
        File(app, "certs").mkdirs()
        File(app, "certs/.env").writeText("$probe=from-dotenv\n")
        return app
    }

    /**
     * A framework distribution just real enough to launch: a play jar that is a loadable
     * -javaagent and holds a play.server.Server reporting the probe variable, plus a
     * testrunner jar whose FirePhoque passes so playAutotest completes.
     */
    private fun fakeFramework(root: File): File {
        val src = File(root.parentFile, "fake-src")
        val classes = File(root.parentFile, "fake-classes").apply { mkdirs() }
        fun source(path: String, body: String) =
            File(src, path).apply { parentFile.mkdirs(); writeText(body) }
        val sources = listOf(
            source("fake/Agent.java", """
                package fake;
                public class Agent {
                    public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
                }
            """.trimIndent()),
            source("play/server/Server.java", """
                package play.server;
                public class Server {
                    public static void main(String[] args) throws Exception {
                        String seen = System.getenv("$probe");
                        System.out.println("$probe=" + (seen == null ? "<unset>" : "[" + seen + "]"));
                        System.out.println("Server is up and running");
                        System.out.flush();
                        // playAutotest (play.id=test) needs the server alive while FirePhoque
                        // runs, and destroys it afterwards.
                        if ("test".equals(System.getProperty("play.id"))) Thread.sleep(60_000);
                    }
                }
            """.trimIndent()),
            source("play/modules/testrunner/FirePhoque.java", """
                package play.modules.testrunner;
                import java.nio.file.*;
                public class FirePhoque {
                    public static void main(String[] args) throws Exception {
                        Path passed = Path.of("test-result", "result.passed");
                        Files.createDirectories(passed.getParent());
                        Files.writeString(passed, "");
                    }
                }
            """.trimIndent()),
        )
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK, not a JRE")
        val rc = javac.run(null, null, null,
            "--release", "25", "-d", classes.absolutePath, *sources.map { it.absolutePath }.toTypedArray())
        assertEquals(0, rc, "compiling the fake framework failed")

        File(root, "framework/lib").mkdirs()
        writeJar(classes, File(root, "framework/play-$fwVersion.jar"))
        writeJar(classes, File(root, "modules/testrunner/lib/play-testrunner.jar"))
        return root
    }

    private fun writeJar(classes: File, dest: File) {
        dest.parentFile.mkdirs()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Premain-Class")] = "fake.Agent"
        }
        JarOutputStream(dest.outputStream(), manifest).use { jar ->
            classes.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(classes).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
    }
}
