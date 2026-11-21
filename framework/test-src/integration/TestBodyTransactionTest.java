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
 * PF-231: the {@code /@tests} runner runs each test class in a job, and by default the class's
 * tests run in that job's transaction, which commits only when the class has finished. A request
 * that a FunctionalTest sends runs in a transaction of its own, so it never saw what the test had
 * saved. With {@code play.test.transaction=none} the tests run with no transaction open, commit
 * what they save, and the request sees it.
 *
 * <p>Each case starts a scratch application in test mode, on an in-memory H2 database, in a child
 * JVM, and runs its test classes through {@code /@tests} as FirePhoque does. The setting belongs
 * to the application, and every integration test boots the one integration testapp.
 */
public class TestBodyTransactionTest {

    private static final String SECRET = "testbodytransactiontestsecret1234567890testbodytransactiontest";

    @Test
    public void byDefaultATestRunsInTheRunnersTransactionAndTheRequestDoesNotSeeWhatItSaved(@TempDir File app)
            throws Exception {
        scratchApp(app, "");
        write(app, "test/AmbientTest.java", "import models.Thing;\n"
                + "import org.junit.jupiter.api.*;\n"
                + "import play.db.jpa.JPA;\n"
                + "public class AmbientTest extends play.test.FunctionalTest {\n"
                + "    @Test\n"
                + "    public void theTestRunsInTheRunnersTransaction() {\n"
                + "        assertTrue(JPA.isInsideTransaction());\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void theRequestDoesNotSeeWhatTheTestSaved() {\n"
                + "        Thing thing = new Thing();\n"
                + "        thing.name = \"pf231\";\n"
                + "        thing.save();\n"
                + "        assertStatus(404, GET(\"/things/\" + thing.id));\n"
                + "    }\n"
                + "}\n");
        Process server = server(app);
        try {
            assertPassed(app, "AmbientTest", 2);
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    @Test
    public void underNoneATestCommitsWhatItSavesAndTheRequestSeesIt(@TempDir File app) throws Exception {
        // in another letter case, as the configuration check accepts it
        scratchApp(app, "play.test.transaction=None\n");
        write(app, "test/things.yml", "Thing(one):\n    name: fixture\n");
        write(app, "test/NoneTest.java", "import models.Thing;\n"
                + "import org.junit.jupiter.api.*;\n"
                + "import play.db.jpa.JPA;\n"
                + "import play.exceptions.JPAException;\n"
                + "import play.jobs.Job;\n"
                + "import play.test.Fixtures;\n"
                + "public class NoneTest extends play.test.FunctionalTest {\n"
                + "    static boolean beforeAllInsideATransaction;\n"
                + "    @BeforeAll\n"
                + "    static void beforeAll() {\n"
                + "        beforeAllInsideATransaction = JPA.isInsideTransaction();\n"
                + "    }\n"
                + "    @BeforeEach\n"
                + "    public void setUp() {\n"
                + "        assertFalse(JPA.isInsideTransaction(), \"@BeforeEach\");\n"
                + "        Fixtures.deleteDatabase();\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void theTestHasNoTransactionOpen() {\n"
                + "        assertFalse(beforeAllInsideATransaction, \"@BeforeAll\");\n"
                + "        assertFalse(JPA.isInsideTransaction());\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void theRequestSeesWhatTheTestCommitted() throws Throwable {\n"
                + "        Thing saved = JPA.withTransaction(\"default\", false, () -> {\n"
                + "            Thing thing = new Thing();\n"
                + "            thing.name = \"pf231\";\n"
                + "            thing.save();\n"
                + "            return thing;\n"
                + "        });\n"
                + "        assertFalse(JPA.isInsideTransaction(), \"withTransaction closed its own\");\n"
                + "        play.mvc.Http.Response response = GET(\"/things/\" + saved.id);\n"
                + "        assertStatus(200, response);\n"
                + "        assertContentEquals(\"pf231\", response);\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void theRequestSeesWhatTheFixturesLoaded() {\n"
                + "        Fixtures.loadModels(\"things.yml\");\n"
                + "        assertContentEquals(\"1 fixture\", GET(\"/things\"));\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void aJobTheTestStartsCommitsInATransactionOfItsOwn() throws Exception {\n"
                + "        Thing saved = new Job<Thing>() {\n"
                + "            @Override\n"
                + "            public Thing doJobWithResult() {\n"
                + "                Thing thing = new Thing();\n"
                + "                thing.name = \"job\";\n"
                + "                thing.save();\n"
                + "                return thing;\n"
                + "            }\n"
                + "        }.now().get();\n"
                + "        assertContentEquals(\"job\", GET(\"/things/\" + saved.id));\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void aJobTheTestCallsDirectlyRunsAsPartOfTheTest() {\n"
                + "        assertFalse(new Job<Boolean>() {\n"
                + "            @Override\n"
                + "            public Boolean doJobWithResult() {\n"
                + "                return JPA.isInsideTransaction();\n"
                + "            }\n"
                + "        }.call());\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void savingWithNoTransactionOpenSaysWhatToDo() {\n"
                + "        Thing thing = new Thing();\n"
                + "        JPAException e = assertThrows(JPAException.class, () -> thing.save());\n"
                + "        assertTrue(e.getMessage().contains(\"play.test.transaction=none\"), e.getMessage());\n"
                + "        assertTrue(e.getMessage().contains(\"JPA.withTransaction(\"), e.getMessage());\n"
                + "    }\n"
                + "}\n");
        // a transaction the class's own code opens in @BeforeEach is still open in @Test, as in plain JUnit
        write(app, "test/OpensItsOwnTest.java", "import models.Thing;\n"
                + "import org.junit.jupiter.api.*;\n"
                + "import play.db.jpa.JPA;\n"
                + "import play.test.Fixtures;\n"
                + "public class OpensItsOwnTest extends play.test.FunctionalTest {\n"
                + "    Long id;\n"
                + "    @BeforeEach\n"
                + "    public void open() {\n"
                + "        Fixtures.deleteDatabase();\n"
                + "        JPA.startTx(\"default\", false);\n"
                + "        Thing thing = new Thing();\n"
                + "        thing.name = \"own\";\n"
                + "        thing.save();\n"
                + "        id = thing.id;\n"
                + "    }\n"
                + "    @Test\n"
                + "    public void theTestSeesWhatItsBeforeEachSaved() {\n"
                + "        Thing found = Thing.findById(id);\n"
                + "        assertEquals(\"own\", found.name);\n"
                + "    }\n"
                + "    @AfterEach\n"
                + "    public void close() {\n"
                + "        JPA.closeTx(\"default\");\n"
                + "        assertContentEquals(\"own\", GET(\"/things/\" + id));\n"
                + "    }\n"
                + "}\n");
        // no PlayJUnitExtension: TestEngine.run sets the job's transaction aside for it
        write(app, "test/PlainTest.java", "public class PlainTest extends org.junit.jupiter.api.Assertions {\n"
                + "    @org.junit.jupiter.api.Test\n"
                + "    public void theTestHasNoTransactionOpen() {\n"
                + "        assertFalse(play.db.jpa.JPA.isInsideTransaction());\n"
                + "    }\n"
                + "}\n");
        Process server = server(app);
        try {
            assertPassed(app, "NoneTest", 6);
            assertPassed(app, "OpensItsOwnTest", 1);
            assertPassed(app, "PlainTest", 1);
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    /** GET /@tests/[testClass].class, as FirePhoque runs a class, and its xunit report. */
    private static void assertPassed(File app, String testClass, int tests) throws Exception {
        HttpResponse<String> run = get("/@tests/" + testClass + ".class");
        File report = new File(app, "test-result/TEST-" + testClass + ".xml");
        assertThat(run.statusCode()).as("%s; report:%n%s%nconsole:%n%s", testClass, read(report), read(console(app)))
                .isEqualTo(200);
        assertThat(read(report)).as("every test ran").contains("failures=\"0\"", "errors=\"0\"", "skipped=\"0\"",
                "tests=\"" + tests + "\"");
    }

    private static void scratchApp(File app, String conf) throws IOException {
        write(app, "conf/application.conf", "application.name=test-body-transaction\napplication.mode=dev\n"
                + "application.secret=${PLAY_SECRET}\n"
                + "db.url=jdbc:h2:mem:pf231;DB_CLOSE_DELAY=-1\ndb.driver=org.h2.Driver\ndb.user=sa\ndb.pass=\n"
                + "jpa.ddl=create\n" + conf);
        write(app, "conf/routes", "GET / Application.index\nGET /things Application.list\n"
                + "GET /things/{id} Application.show\n");
        write(app, "app/models/Thing.java", "package models;\n"
                + "@jakarta.persistence.Entity\n"
                + "public class Thing extends play.db.jpa.Model {\n"
                + "    public String name;\n"
                + "}\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "import java.util.List;\n"
                + "import models.Thing;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() {\n"
                + "        renderText(\"ok\");\n"
                + "    }\n"
                + "    public static void list() {\n"
                + "        List<Thing> things = Thing.findAll();\n"
                + "        StringBuilder text = new StringBuilder().append(things.size());\n"
                + "        for (Thing thing : things) {\n"
                + "            text.append(' ').append(thing.name);\n"
                + "        }\n"
                + "        renderText(text);\n"
                + "    }\n"
                + "    public static void show(Long id) {\n"
                + "        Thing thing = Thing.findById(id);\n"
                + "        notFoundIfNull(thing);\n"
                + "        renderText(thing.name);\n"
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

    /** From the server of the running case. */
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
