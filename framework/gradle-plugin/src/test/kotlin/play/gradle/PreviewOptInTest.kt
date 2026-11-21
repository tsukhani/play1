package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

// As JulBridgeArgsTest: the forked JVMs and the detached playStart child are unverified on
// Windows, and playAotGen refuses there.
private const val FORKS_JVMS = "forks detached JVMs from TestKit, and aot-gen refuses on Windows"

/**
 * PF-229: `java.enablePreview=true` in application.conf is the one opt-in for Java's preview
 * features. Play's own javac reads it (ApplicationCompilerTest and JavacDevModeTest in the
 * framework); here, Gradle's compileJava and compileTestJava compile with --enable-preview and
 * Gradle's test task runs with it, and every Play JVM the plugin starts gets the option, which a
 * class compiled that way needs to load. BundleLauncherTest holds the bundle launcher's
 * conf_jvm_args to the same flags as confJvmArgs.
 */
class PreviewOptInTest {

    private val fwVersion = "9.9.9-TEST"

    @Test
    fun `compileJava and compileTestJava compile a preview API into preview classes and the test task runs with the flag`(@TempDir tmp: File) {
        val app = writeApp(tmp, conf = "java.enablePreview=true\n")
        val usesPreview = """
            public static String value() {
                StableValue<String> value = StableValue.of();
                return value.orElseSet(() -> "preview");
            }
        """.trimIndent()
        File(app, "app/models").mkdirs()
        File(app, "app/models/Lazy.java").writeText("package models;\npublic class Lazy {\n$usesPreview\n}\n")
        File(app, "test/models").mkdirs()
        File(app, "test/models/LazyCheck.java").writeText("package models;\npublic class LazyCheck {\n$usesPreview\n}\n")
        File(app, "build.gradle.kts").appendText(
            """
            tasks.register("printTestJvmArgs") {
                val test = tasks.named<Test>("test")
                doLast { println("TEST_JVM_ARGS=" + test.get().allJvmArgs) }
            }
            """.trimIndent() + "\n"
        )

        val build = TestProject.runner(app, "compileJava", "compileTestJava", "printTestJvmArgs").build()

        // the mark the JVM refuses to load without --enable-preview, as javac writes it for Play
        assertEquals(PREVIEW_MINOR_VERSION, minorVersion(File(app, "build/classes/java/main/models/Lazy.class")))
        assertEquals(PREVIEW_MINOR_VERSION, minorVersion(File(app, "build/classes/java/test/models/LazyCheck.class")))
        val testJvmArgs = build.output.lines().single { it.startsWith("TEST_JVM_ARGS=") }
        assertTrue("--enable-preview" in testJvmArgs, testJvmArgs)

        // Without the key, javac refuses the preview API, and a changed key recompiles.
        File(app, "conf/application.conf").writeText("application.name=testapp\n")
        val refused = TestProject.runner(app, "compileJava").buildAndFail()
        assertTrue("StableValue is a preview API and is disabled by default" in refused.output, refused.output)
    }

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playTest", "playPrecompile", "playStart", "playRestart", "playAutotest"])
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `every Play JVM the plugin starts gets --enable-preview from the key and none without it`(task: String, @TempDir tmp: File) {
        val app = writeApp(tmp, conf = "application.name=testapp\n")
        assertFalse("--enable-preview" in jvmArgs(app, task), "$task without the key")

        File(app, "conf/application.conf").writeText("java.enablePreview=true\n")
        assertTrue("--enable-preview" in jvmArgs(app, task), task)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen trains a JVM that has --enable-preview and a start with the flag runs from that cache`(@TempDir tmp: File) {
        val app = writeApp(tmp, conf = "java.enablePreview=true\n")

        TestProject.runner(app, "playAotGen", "-PhttpPort=${freePort()}", "-PjvmArgs=-Dpf229.mode=listen").build()

        val training = File(app, "logs/aot-gen.out").readText()
        assertTrue("PREVIEW_FLAG=true" in training, training)
        assertTrue(File(app, "build/play/aot/app.aot").length() > 1_000_000, "no cache was written:\n$training")
        // -XX:AOTMode=on: the JVM starts only when it can use the cache, preview flag and all
        val start = jvmArgs(app, "playStart", "-PjvmArgs=-Dprecompiled=true -XX:AOTMode=on")
        assertTrue("--enable-preview" in start, "$start")
        assertTrue(start.any { it.startsWith("-XX:AOTCache=") }, "$start")
    }

    /** Run [task] and return the arguments its JVM reports it was started with. */
    private fun jvmArgs(app: File, task: String, vararg args: String): List<String> {
        val pidFile = File(app, "server.pid").apply { delete() }
        val systemOut = File(app, "logs/system.out").apply { delete() }
        // An explicit free port for playAutotest, which POSTs /@kill to its port before it starts.
        val portArgs = if (task == "playAutotest") arrayOf("-PhttpPort=${freePort()}") else emptyArray()
        val result = TestProject.runner(app, task, *portArgs, *args).build()
        val output = if (task in listOf("playRun", "playTest", "playPrecompile")) result.output else {
            // the other three redirect the JVM's output to logs/system.out, two of them from a detached child
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline && !(systemOut.isFile && "REPORT DONE" in systemOut.readText())) {
                Thread.sleep(50)
            }
            if (systemOut.isFile) systemOut.readText() else ""
        }
        if ("REPORT DONE" !in output) fail<Unit>("$task's JVM never reported:\n$output")
        if (pidFile.isFile) {
            ProcessHandle.of(pidFile.readText().trim().toLong()).ifPresent { it.onExit().get(30, TimeUnit.SECONDS) }
        }
        return output.lines().map { it.trimEnd('\r') }.filter { it.startsWith("JVMARG=") }.map { it.substringAfter('=') }
    }

    private fun minorVersion(classFile: File): Int =
        DataInputStream(classFile.inputStream()).use { it.readInt(); it.readUnsignedShort() }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun writeApp(tmp: File, conf: String): File {
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
        // playAutotest refuses to start without both files, and playAotGen without precompiled classes.
        File(app, "conf/routes").writeText("")
        File(app, "conf/application.conf").writeText(conf)
        File(app, "precompiled/java").mkdirs()
        return app
    }

    /**
     * A framework just real enough to launch: a play jar that is a loadable -javaagent and holds a
     * play.server.Server that reports the JVM's arguments and returns, or, with -Dpf229.mode=listen,
     * answers HTTP until it is stopped, as a training run needs; and a testrunner jar whose
     * FirePhoque passes, so playAutotest completes.
     */
    private fun fakeFramework(root: File): File {
        val src = File(root.parentFile, "fake-src")
        val classes = File(root.parentFile, "fake-classes").apply { mkdirs() }
        fun source(path: String, body: String) = File(src, path).apply { parentFile.mkdirs(); writeText(body) }
        val sources = listOf(
            source("play/server/Server.java", """
                package play.server;
                import java.io.BufferedReader;
                import java.io.InputStreamReader;
                import java.lang.management.ManagementFactory;
                import java.net.ServerSocket;
                import java.net.Socket;
                import java.util.Arrays;
                public class Server {
                    public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
                    public static void main(String[] args) throws Exception {
                        boolean preview = ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview");
                        if ("listen".equals(System.getProperty("pf229.mode"))) {
                            System.out.println("PREVIEW_FLAG=" + preview);
                            System.out.flush();
                            int port = Arrays.stream(args).filter(a -> a.startsWith("--http.port="))
                                .mapToInt(a -> Integer.parseInt(a.substring("--http.port=".length()))).findFirst().orElseThrow();
                            try (ServerSocket server = new ServerSocket(port)) {
                                while (true) {
                                    try (Socket socket = server.accept()) {
                                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                                        String request = in.readLine();
                                        if (request == null) continue;
                                        for (String h = in.readLine(); h != null && !h.isEmpty(); h = in.readLine()) {}
                                        socket.getOutputStream().write(
                                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes());
                                    } catch (java.io.IOException e) {
                                        // a client that hung up
                                    }
                                }
                            }
                        }
                        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) System.out.println("JVMARG=" + arg);
                        System.out.println("REPORT DONE");
                        System.out.println("Server is up and running");
                        System.out.flush();
                        // playAutotest's server has to stay up while FirePhoque runs; it is stopped afterwards
                        boolean autotest = "test".equals(System.getProperty("play.id")) && System.getProperty("precompile") == null
                            && Arrays.stream(args).anyMatch(a -> a.startsWith("--http.port="));
                        if (autotest) Thread.sleep(60_000);
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
            mainAttributes[Attributes.Name("Premain-Class")] = "play.server.Server"
        }
        JarOutputStream(dest.outputStream(), manifest).use { jar ->
            classes.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(classes).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
    }

    private companion object {
        const val PREVIEW_MINOR_VERSION = 65535
    }
}
