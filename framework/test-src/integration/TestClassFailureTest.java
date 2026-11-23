package integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.server.Server;

/**
 * PF-241: {@code /@tests} recorded the results of tests only, so a class that failed as a whole, before or after its
 * tests, recorded nothing and was reported as passed: a {@code @BeforeAll} that threw (an application's cache test on
 * a machine without the cache server), an {@code @AfterAll}, a class initialiser, an extension's {@code beforeAll},
 * a nested class Jupiter could not create, and a class with no test at all. Each now fails, with what failed, as
 * PF-230's JUnit Platform runner reports it; a class skipped as a whole still passes, reported as skipped. Boots a
 * scratch test-mode application in a JVM of its own and runs each class through {@code /@tests} as FirePhoque does.
 */
public class TestClassFailureTest {

    private static final String SECRET = "testclassfailuretestsecret1234567890testclassfailuretest12345";

    @Test
    public void aClassThatFailsAsAWholeIsReportedAsFailedAndOneSkippedAsAWholeAsSkipped(@TempDir File app)
            throws Exception {
        scratchApp(app);
        test(app, "BeforeAllFailsTest", "public class BeforeAllFailsTest extends play.test.UnitTest {\n"
                + "    @BeforeAll\n"
                + "    public static void setUp() {\n"
                + "        fail(\"the cache pool is missing\");\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void neverRuns() {\n"
                + "    }\n"
                + "}\n");
        test(app, "AfterAllFailsTest", "public class AfterAllFailsTest extends play.test.UnitTest {\n"
                + "    @Test\n"
                + "    public void passes() {\n"
                + "    }\n"
                + "    @AfterAll\n"
                + "    public static void tearDown() {\n"
                + "        fail(\"the teardown broke\");\n"
                + "    }\n"
                + "}\n");
        test(app, "StaticInitFailsTest", "public class StaticInitFailsTest extends play.test.UnitTest {\n"
                + "    static final int N = Integer.parseInt(System.getProperty(\"pf241.absent\", \"not a number\"));\n"
                + "    @BeforeAll\n"
                + "    public static void setUp() {\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void neverRuns() {\n"
                + "    }\n"
                + "}\n");
        test(app, "ExtensionFailsTest", "@ExtendWith(ExtensionFailsTest.Refuses.class)\n"
                + "public class ExtensionFailsTest extends play.test.UnitTest {\n"
                + "    public static class Refuses implements BeforeAllCallback {\n"
                + "        public void beforeAll(ExtensionContext context) {\n"
                + "            throw new IllegalStateException(\"the extension refused\");\n"
                + "        }\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void neverRuns() {\n"
                + "    }\n"
                + "}\n");
        test(app, "NestedRefusedTest", "public class NestedRefusedTest extends play.test.UnitTest {\n"
                + "    @Test\n"
                + "    public void passes() {\n"
                + "    }\n"
                + "    @Nested\n"
                + "    @TestInstance(TestInstance.Lifecycle.PER_CLASS)\n"
                + "    class Inner {\n"
                + "        Inner(StringBuilder nothingResolvesThis) {\n"
                + "        }\n"
                + "        @Test\n"
                + "        public void neverRuns() {\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        test(app, "EmptyTest", "public class EmptyTest extends play.test.UnitTest {\n"
                + "    public void notATest() {\n"
                + "    }\n"
                + "}\n");
        test(app, "AbortedSetupTest", "public class AbortedSetupTest extends play.test.UnitTest {\n"
                + "    @BeforeAll\n"
                + "    public static void setUp() {\n"
                + "        Assumptions.abort(\"no browser on this machine\");\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void neverRuns() {\n"
                + "    }\n"
                + "}\n");
        test(app, "DisabledClassTest", "@Disabled(\"not on this machine\")\n"
                + "public class DisabledClassTest extends play.test.UnitTest {\n"
                + "    @Test\n"
                + "    public void neverRuns() {\n"
                + "    }\n"
                + "}\n");
        test(app, "PassingTest", "public class PassingTest extends play.test.UnitTest {\n"
                + "    @Test\n"
                + "    public void passes() {\n"
                + "    }\n"
                + "}\n");

        Process server = server(app);
        try {
            assertFailed(app, "BeforeAllFailsTest", "the cache pool is missing", "BeforeAllFailsTest.java, line 6");
            assertFailed(app, "AfterAllFailsTest", "the teardown broke", "AfterAllFailsTest.java, line 9");
            assertFailed(app, "StaticInitFailsTest", "ExceptionInInitializerError", "StaticInitFailsTest.java, line 4");
            assertFailed(app, "ExtensionFailsTest", "the extension refused", "ExtensionFailsTest.java, line 7");
            // Jupiter refuses the nested class: Play's enhancer gives it a constructor beside the one it declares
            assertFailed(app, "NestedRefusedTest", "Inner (the class's setup or teardown)", "NestedRefusedTest$Inner");
            assertFailed(app, "EmptyTest", "No test ran", "EmptyTest");
            assertPassed(app, "AbortedSetupTest", "no browser on this machine");
            assertPassed(app, "DisabledClassTest", "not on this machine");
            assertPassed(app, "PassingTest", "passes");
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    /** GET /@tests/[testClass].class, as FirePhoque runs a class: a 500 and a .failed.html naming what failed. */
    private static void assertFailed(File app, String testClass, String... shown) throws Exception {
        HttpResponse<String> run = get("/@tests/" + testClass + ".class");
        File failed = new File(app, "test-result/" + testClass + ".class.failed.html");
        assertThat(run.statusCode()).as("%s; console:%n%s", testClass, read(console(app))).isEqualTo(500);
        assertThat(failed).as(testClass).isFile();
        assertThat(new File(app, "test-result/" + testClass + ".class.passed.html")).doesNotExist();
        assertThat(read(failed)).as(testClass).contains(shown);
    }

    private static void assertPassed(File app, String testClass, String shown) throws Exception {
        HttpResponse<String> run = get("/@tests/" + testClass + ".class");
        File passed = new File(app, "test-result/" + testClass + ".class.passed.html");
        assertThat(run.statusCode()).as("%s; console:%n%s", testClass, read(console(app))).isEqualTo(200);
        assertThat(read(passed)).as(testClass).contains(shown);
    }

    private static void test(File app, String name, String body) throws IOException {
        write(app, "test/" + name + ".java", "import org.junit.jupiter.api.*;\n"
                + "import org.junit.jupiter.api.extension.*;\n" + body);
    }

    private static void scratchApp(File app) throws IOException {
        write(app, "conf/application.conf", "application.name=test-class-failure\napplication.mode=dev\n"
                + "application.secret=${PLAY_SECRET}\n");
        write(app, "conf/routes", "GET / Application.index\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() {\n"
                + "        renderText(\"ok\");\n"
                + "    }\n"
                + "}\n");
    }

    private static int port;

    /** play.server.Server on [app] in test mode, as play test starts it, started by its first request. */
    private static Process server(File app) throws Exception {
        File testrunner = Path.of(System.getProperty("user.dir"), "..", "modules", "testrunner", "lib",
                "play-testrunner.jar").toFile();
        assertThat(testrunner).as("built by ant modules").isFile();
        port = freePort();
        List<String> cmd = List.of(java(), "--enable-native-access=ALL-UNNAMED",
                "-DPLAY_SECRET=" + SECRET, "-Dapplication.path=" + app.getAbsolutePath(), "-Dplay.id=test",
                "-Dlog4j2.shutdownHookEnabled=false",
                "-cp", System.getProperty("java.class.path") + File.pathSeparator + testrunner.getAbsolutePath(),
                Server.class.getName(), "--http.port=" + port);
        Process server = new ProcessBuilder(cmd).directory(app).redirectErrorStream(true).redirectOutput(console(app)).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            assertThat(server.isAlive()).as("the JVM is running; console:%n%s", read(console(app))).isTrue();
            try {
                assertThat(get("/").body()).as("console:%n%s", read(console(app))).isEqualTo("ok");
                return server;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        server.destroyForcibly().waitFor();
        throw new AssertionError("GET / never answered; console:\n" + read(console(app)));
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static File console(File app) {
        return new File(app, "server.out");
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String read(File file) throws IOException {
        return file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
    }

    private static void write(File app, String path, String content) throws IOException {
        Path file = app.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
