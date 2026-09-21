package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
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
 * PF-175: every Play JVM the plugin forks must set java.util.logging.manager to log4j-jul's
 * LogManager, and an app that sets the property itself must keep its own value. The plugin
 * builds that argv in three independent places — registerPlayJvmTask (playRun), spawnPlay
 * (playStart) and playAutotest — so passing one says nothing about the others. One site
 * missing it is exactly the failure this guards: an app bridged under playStart but not
 * under playAutotest.
 *
 * The argv alone would not prove "the app wins", which depends on where each site places the
 * arg relative to conf and command-line args. So each task runs for real against a fake
 * framework whose play.server.Server prints the value the JVM actually resolved.
 */
// The forked JVMs and the detached playStart child are unverified on Windows, where a child
// still holding its files can fail @TempDir cleanup; the argv code under test is OS-neutral.
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks detached JVMs from TestKit; unverified on Windows")
class JulBridgeArgsTest {

    private val fwVersion = "9.9.9-TEST"
    private val log4jManager = "org.apache.logging.log4j.jul.LogManager"

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playStart", "playAutotest"])
    fun `forked Play JVM routes java util logging into log4j2`(task: String, @TempDir tmp: File) {
        val app = writeApp(tmp, confExtra = "")
        assertEquals(log4jManager, resolvedJulManager(app, task))
    }

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playStart", "playAutotest"])
    fun `an app-set java util logging manager is not overridden`(task: String, @TempDir tmp: File) {
        // jvm.memory is the one conf channel all three sites lift onto the command line.
        val app = writeApp(tmp, confExtra = "jvm.memory=-Djava.util.logging.manager=com.example.AppLogManager")
        assertEquals("com.example.AppLogManager", resolvedJulManager(app, task))
    }

    @Test
    fun `a build script systemProperty overrides playRun's JUL manager`(@TempDir tmp: File) {
        // Gradle emits systemProperties ahead of jvmArgs, so this channel only works because
        // registerPlayJvmTask sets the default as a systemProperty too; as a jvmArgs -D it won.
        val app = writeApp(tmp, confExtra = "")
        File(app, "build.gradle.kts").appendText(
            """
            tasks.named<JavaExec>("playRun") {
                systemProperty("java.util.logging.manager", "com.example.ScriptLogManager")
            }
            """.trimIndent() + "\n"
        )
        assertEquals("com.example.ScriptLogManager", resolvedJulManager(app, "playRun"))
    }

    /** Run [task] and return the java.util.logging.manager its forked JVM resolved. */
    private fun resolvedJulManager(app: File, task: String): String {
        // An explicit free port: playAutotest POSTs /@kill to its port before and after the
        // run, which must never reach a real Play server on this machine's default 9000.
        val port = ServerSocket(0).use { it.localPort }
        val result = TestProject.runner(app, task, "-PhttpPort=$port").build()
        // playRun forwards the JVM's stdout into the build output; the other two redirect it
        // to logs/system.out, and playStart returns before its detached child has written.
        val out = if (task == "playRun") result.output else awaitSystemOut(app)
        return Regex("JUL_MANAGER=(\\S+)").find(out)?.groupValues?.get(1)
            ?: fail("$task's JVM never reported its JUL manager:\n$out")
    }

    private fun awaitSystemOut(app: File): String {
        val systemOut = File(app, "logs/system.out")
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (systemOut.isFile && systemOut.readText().contains("JUL_MANAGER=")) return systemOut.readText()
            Thread.sleep(100)
        }
        return if (systemOut.isFile) systemOut.readText() else ""
    }

    private fun writeApp(tmp: File, confExtra: String): File {
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
        File(app, "conf/application.conf").writeText("application.name=testapp\n$confExtra\n")
        return app
    }

    /**
     * A framework distribution just real enough to launch: a play jar that is a loadable
     * -javaagent and holds a play.server.Server reporting the resolved property, plus a
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
                        System.out.println("JUL_MANAGER=" + System.getProperty("java.util.logging.manager"));
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
