package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/**
 * PF-206: playAutotest passes a run only when FirePhoque exits 0 and the app's
 * test-result holds result.passed. The marker alone is written by whichever server
 * answers /@tests/end on the port, and a run whose classes went ERROR once printed
 * "All tests passed" beside a passed marker that another run had left there.
 *
 * The real FirePhoque runs here, compiled from modules/testrunner against its own
 * htmlunit jars, so its exit code and its check of the server's results folder are
 * what is tested. The Play server is a fake whose answers each case scripts in
 * scenario.properties: the outcome per test class (passed, failed, or error, which
 * writes no result file), a marker to write whatever verdict /@tests/end carries,
 * and the results folder /@tests.list names. It logs every path it is asked for.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks detached JVMs from TestKit; unverified on Windows")
class AutotestVerdictTest {

    private val fwVersion = "9.9.9-TEST"

    @Test
    fun `a run whose classes all pass passes`(@TempDir tmp: File) {
        val app = writeApp(tmp, "")

        val result = autotest(app).build()

        assertTrue("All tests passed" in result.output, result.output)
        assertEquals(2, result.output.lines().count { it.contains("PASSED") }, result.output)
    }

    @Test
    fun `a class that went ERROR fails the run even beside a passed marker`(@TempDir tmp: File) {
        // What happened on 2026-11-13: classes ERROR, and result.passed in test-result.
        val app = writeApp(tmp, "BTest=error\nmarker=passed\n")

        val result = autotest(app).buildAndFail()

        assertTrue(File(app, "test-result/result.passed").isFile, "the case needs the planted marker")
        assertTrue(result.output.lines().any { "BTest" in it && "ERROR" in it }, result.output)
        assertTrue("Tests failed (FirePhoque exit=1)" in result.output, result.output)
        assertTrue("result.passed is ignored" in result.output, result.output)
        assertFalse("All tests passed" in result.output, result.output)
    }

    @Test
    fun `a FAILED class fails the run as before`(@TempDir tmp: File) {
        val app = writeApp(tmp, "BTest=failed\n")

        val result = autotest(app).buildAndFail()

        assertTrue(File(app, "test-result/result.failed").isFile, result.output)
        assertTrue("Tests failed (FirePhoque exit=1)" in result.output, result.output)
        assertFalse("All tests passed" in result.output, result.output)
    }

    @Test
    fun `a server that writes to another application's folder is refused before anything runs`(@TempDir tmp: File) {
        val other = File(tmp, "other-app/test-result")
        val app = writeApp(tmp, "results=${other.absolutePath.replace("\\", "\\\\")}\n")

        val result = autotest(app).buildAndFail()

        assertTrue("is not this application's" in result.output && other.absolutePath in result.output, result.output)
        assertTrue("Tests did not successfully complete (FirePhoque exit=1)" in result.output, result.output)
        // /@tests/init would have deleted the other application's results.
        assertEquals(listOf("/@tests.list"), requests(app).filter { it.startsWith("/@tests") }, result.output)
        assertFalse(other.exists(), "nothing was written to the other application's folder")
    }

    /** playAutotest on an explicit free port, so its /@kill never reaches a real server. */
    private fun autotest(app: File) =
        TestProject.runner(app, "playAutotest", "-PhttpPort=${ServerSocket(0).use { it.localPort }}")

    private fun requests(app: File): List<String> =
        File(app, "requests.log").takeIf { it.isFile }?.readLines().orEmpty()

    private fun writeApp(tmp: File, scenario: String): File {
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
        File(app, "conf/routes").writeText("")
        File(app, "conf/application.conf").writeText("application.name=testapp\n")
        File(app, "scenario.properties").writeText(scenario)
        return app
    }

    /**
     * A framework distribution with the real FirePhoque and a fake Play server: a play
     * jar that is a loadable -javaagent and holds play.server.Server, which serves the
     * testrunner's endpoints for two unit-test classes, ATest and BTest.
     */
    private fun fakeFramework(root: File): File {
        val repo = File("../..").canonicalFile
        val testrunner = File(repo, "modules/testrunner")
        val firephoqueJars = File(testrunner, "firephoque").listFiles { f -> f.name.endsWith(".jar") }!!.toList()
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK, not a JRE")

        val src = File(root.parentFile, "fake-src")
        val serverClasses = File(root.parentFile, "fake-classes").apply { mkdirs() }
        fun source(path: String, body: String) = File(src, path).apply { parentFile.mkdirs(); writeText(body) }
        val sources = listOf(
            source("fake/Agent.java", """
                package fake;
                public class Agent {
                    public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
                }
            """.trimIndent()),
            source("play/server/Server.java", """
                package play.server;
                import com.sun.net.httpserver.HttpServer;
                import java.io.Reader;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.*;
                import java.util.Properties;
                public class Server {
                    public static void main(String[] args) throws Exception {
                        int port = 9000;
                        for (String a : args) if (a.startsWith("--http.port=")) port = Integer.parseInt(a.substring(12));
                        Path app = Path.of(System.getProperty("application.path"));
                        Properties scenario = new Properties();
                        try (Reader r = Files.newBufferedReader(app.resolve("scenario.properties"))) { scenario.load(r); }
                        Path results = Path.of(scenario.getProperty("results", app.resolve("test-result").toString()));
                        Path log = app.resolve("requests.log");
                        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
                        http.createContext("/", ex -> {
                            String path = ex.getRequestURI().getPath();
                            String query = ex.getRequestURI().getQuery();
                            synchronized (Server.class) {
                                Files.writeString(log, path + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            }
                            String body = "done";
                            if (path.equals("/@tests.list")) {
                                body = "---\n" + results + "\nU:ATest.class\nU:BTest.class\n";
                            } else if (path.equals("/@tests/init")) {
                                Files.createDirectories(results);
                            } else if (path.equals("/@tests/end")) {
                                String verdict = scenario.getProperty("marker", query.substring("result=".length()));
                                Files.writeString(results.resolve("result." + verdict), verdict);
                            } else if (path.startsWith("/@tests/") && path.endsWith(".class")) {
                                String test = path.substring("/@tests/".length());
                                String outcome = scenario.getProperty(test.substring(0, test.length() - 6), "passed");
                                if (!outcome.equals("error")) Files.writeString(results.resolve(test + "." + outcome + ".html"), outcome);
                            }
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            ex.sendResponseHeaders(200, bytes.length);
                            ex.getResponseBody().write(bytes);
                            ex.close();
                        });
                        http.start();
                        System.out.println("Server is up and running");
                        System.out.flush();
                    }
                }
            """.trimIndent()),
        )
        assertEquals(0, javac.run(null, null, null,
            "--release", "25", "-d", serverClasses.absolutePath, *sources.map { it.absolutePath }.toTypedArray()),
            "compiling the fake server failed")

        val firephoqueClasses = File(root.parentFile, "firephoque-classes").apply { mkdirs() }
        assertEquals(0, javac.run(null, null, null,
            "--release", "25", "-d", firephoqueClasses.absolutePath,
            "-classpath", firephoqueJars.joinToString(File.pathSeparator) { it.absolutePath },
            File(testrunner, "src/play/modules/testrunner/FirePhoque.java").absolutePath),
            "compiling the real FirePhoque failed")

        File(root, "framework/lib").mkdirs()
        writeJar(serverClasses, File(root, "framework/play-$fwVersion.jar"))
        writeJar(firephoqueClasses, File(root, "modules/testrunner/lib/play-testrunner.jar"))
        firephoqueJars.forEach { it.copyTo(File(root, "modules/testrunner/firephoque/${it.name}")) }
        File(testrunner, "conf").copyRecursively(File(root, "modules/testrunner/conf"))
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
