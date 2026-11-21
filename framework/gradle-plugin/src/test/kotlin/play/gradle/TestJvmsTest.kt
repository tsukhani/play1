package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket

/**
 * PF-230: Gradle's test task runs an application's test/ classes on the JUnit Platform, in
 * play1 { testJvms } JVMs, each a test-mode application of its own that runs its classes one at
 * a time. The application here is a real one, on this checkout's framework (play-<version>.jar,
 * which `ant jar` builds, and framework/lib), so that Play itself starts in every test JVM: the
 * classes come from Play's own compile, each class runs in a job of its own as under /@tests,
 * and the HTTP server of each JVM listens on a port of its own.
 *
 * The configuration's %test.http.port names a port this test holds, so a JVM that bound it
 * would fail to start, and its secret variable is one no environment sets, which the task then
 * generates.
 */
// Forks JVMs from TestKit, as JulBridgeArgsTest does; unverified on Windows.
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks JVMs from TestKit; unverified on Windows")
class TestJvmsTest {

    private val repo = File("../..").canonicalFile
    private val version = Regex("""name="baseversion"\s+value="([^"]+)"""")
        .find(File(repo, "framework/build.xml").readText())!!.groupValues[1]

    @Test
    fun `two JVMs keep a static and a server each, a class runs in its transaction, and a failure names its assertion`(@TempDir tmp: File) {
        ServerSocket(0).use { held ->
            val app = app(tmp, conf = "%test.http.port=${held.localPort}\n")
            for (n in listOf("One", "Two")) write(app, "test/Static${n}Test.java", staticTest(n))
            write(app, "test/AmbientTest.java", """
                import models.Thing;
                import org.junit.jupiter.api.*;
                import play.db.jpa.JPA;
                @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
                public class AmbientTest extends play.test.FunctionalTest {
                    static Long saved;
                    @Test @Order(1)
                    public void theTestRunsInTheClassTransaction() {
                        assertTrue(JPA.isInsideTransaction());
                        Thing thing = new Thing();
                        thing.name = "pf230";
                        thing.save();
                        saved = thing.id;
                    }
                    @Test @Order(2)
                    public void theNextTestSeesWhatTheFirstSaved() {
                        assertEquals("pf230", Thing.<Thing>findById(saved).name);
                    }
                    @Test @Order(3)
                    public void theRequestDoesNotSeeItBeforeTheClassCommits() {
                        assertStatus(404, GET("/things/" + saved));
                    }
                }
            """.trimIndent())
            write(app, "test/FailingTest.java", """
                public class FailingTest extends play.test.UnitTest {
                    @org.junit.jupiter.api.Test
                    public void fails() {
                        int answer = 3;
                        assertEquals(2, answer, "pf230 the answer");
                    }
                }
            """.trimIndent())

            val all = TestProject.runner(app, "test").buildAndFail()

            assertTrue("~ Generated ephemeral PF230_SECRET for this test run" in all.output, all.output)
            // the console names the failing assertion, as test-result/*.failed.html does
            assertTrue("FailingTest > fails() FAILED" in all.output, all.output)
            assertTrue("pf230 the answer ==> expected: <2> but was: <3>" in all.output, all.output)
            val failing = report(app, "FailingTest")
            assertTrue(Regex("""<failure message="[^"]*pf230 the answer ==&gt; expected: &lt;2&gt; but was: &lt;3&gt;""").containsMatchIn(failing), failing)
            assertTrue("at FailingTest.fails(FailingTest.java:5)" in failing, failing)
            assertPassed(app, "AmbientTest", 3)

            val one = jvmOf(app, "StaticOneTest")
            val two = jvmOf(app, "StaticTwoTest")
            assertNotEquals(one.pid, two.pid, "the two classes ran in one JVM")
            assertNotEquals(one.port, two.port, "the two JVMs shared a port")
            assertTrue(held.localPort !in listOf(one.port, two.port), "a JVM bound %test.http.port")
            assertEquals(setOf("1", "2"), setOf(one.number, two.number), "play.test.jvm")

            // In one JVM the second class sees what the first set.
            val shared = TestProject.runner(app, "test", "-PtestJvms=1", "--tests", "StaticOneTest", "--tests", "StaticTwoTest").buildAndFail()
            assertTrue("another class set it in this JVM ==> expected: <null> but was: <" in shared.output, shared.output)
            assertEquals(1, Regex("Static(One|Two)Test > ownsTheStaticAndItsServer\\(\\) FAILED").findAll(shared.output).count(), shared.output)
        }
    }

    @Test
    fun `--tests runs one class or one method`(@TempDir tmp: File) {
        val app = app(tmp, conf = "")
        write(app, "test/FirstTest.java", """
            public class FirstTest extends play.test.UnitTest {
                @org.junit.jupiter.api.Test public void a() {}
                @org.junit.jupiter.api.Test public void b() {}
            }
        """.trimIndent())
        write(app, "test/SecondTest.java", """
            public class SecondTest extends play.test.UnitTest {
                @org.junit.jupiter.api.Test public void c() {}
            }
        """.trimIndent())

        TestProject.runner(app, "test", "--tests", "FirstTest").build()
        assertEquals(listOf("TEST-FirstTest.xml"), reports(app))
        assertPassed(app, "FirstTest", 2)

        TestProject.runner(app, "test", "--tests", "FirstTest.b").build()
        assertEquals(listOf("TEST-FirstTest.xml"), reports(app))
        assertPassed(app, "FirstTest", 1)
        assertTrue("""<testcase name="b()" classname="FirstTest"""" in report(app, "FirstTest"))
    }

    @Test
    fun `under play test transaction none a class has no transaction, as under the tests runner`(@TempDir tmp: File) {
        // PF-231's suite for /@tests (TestBodyTransactionTest), with java.enablePreview, so that
        // every class Play compiles carries the preview mark a JVM without the flag refuses.
        val app = app(tmp, conf = "play.test.transaction=None\njava.enablePreview=true\n")
        write(app, "app/models/Lazy.java", """
            package models;
            public class Lazy {
                public static String value() {
                    StableValue<String> value = StableValue.of();
                    return value.orElseSet(() -> "preview");
                }
            }
        """.trimIndent())
        write(app, "test/things.yml", "Thing(one):\n    name: fixture\n")
        write(app, "test/NoneTest.java", """
            import models.Thing;
            import org.junit.jupiter.api.*;
            import play.db.jpa.JPA;
            import play.exceptions.JPAException;
            import play.jobs.Job;
            import play.test.Fixtures;
            public class NoneTest extends play.test.FunctionalTest {
                static boolean beforeAllInsideATransaction;
                @BeforeAll
                static void beforeAll() {
                    beforeAllInsideATransaction = JPA.isInsideTransaction();
                }
                @BeforeEach
                public void setUp() {
                    assertFalse(JPA.isInsideTransaction(), "@BeforeEach");
                    Fixtures.deleteDatabase();
                }
                @Test
                public void theTestHasNoTransactionOpen() {
                    assertFalse(beforeAllInsideATransaction, "@BeforeAll");
                    assertFalse(JPA.isInsideTransaction());
                }
                @Test
                public void theRequestSeesWhatTheTestCommitted() throws Throwable {
                    Thing saved = JPA.withTransaction("default", false, () -> {
                        Thing thing = new Thing();
                        thing.name = "pf231";
                        thing.save();
                        return thing;
                    });
                    play.mvc.Http.Response response = GET("/things/" + saved.id);
                    assertStatus(200, response);
                    assertContentEquals("pf231", response);
                }
                @Test
                public void theRequestSeesWhatTheFixturesLoaded() {
                    Fixtures.loadModels("things.yml");
                    assertContentEquals("1 fixture", GET("/things"));
                }
                @Test
                public void aJobTheTestCallsDirectlyRunsAsPartOfTheTest() {
                    assertFalse(new Job<Boolean>() {
                        @Override
                        public Boolean doJobWithResult() {
                            return JPA.isInsideTransaction();
                        }
                    }.call());
                }
                @Test
                public void savingWithNoTransactionOpenSaysWhatToDo() {
                    Thing thing = new Thing();
                    JPAException e = assertThrows(JPAException.class, () -> thing.save());
                    assertTrue(e.getMessage().contains("play.test.transaction=none"), e.getMessage());
                }
                @Test
                public void aPreviewClassLoads() {
                    assertEquals("preview", models.Lazy.value());
                }
            }
        """.trimIndent())
        write(app, "test/OpensItsOwnTest.java", """
            import models.Thing;
            import org.junit.jupiter.api.*;
            import play.db.jpa.JPA;
            import play.test.Fixtures;
            public class OpensItsOwnTest extends play.test.FunctionalTest {
                Long id;
                @BeforeEach
                public void open() {
                    Fixtures.deleteDatabase();
                    JPA.startTx("default", false);
                    Thing thing = new Thing();
                    thing.name = "own";
                    thing.save();
                    id = thing.id;
                }
                @Test
                public void theTestSeesWhatItsBeforeEachSaved() {
                    assertEquals("own", Thing.<Thing>findById(id).name);
                }
                @AfterEach
                public void close() {
                    JPA.closeTx("default");
                    assertContentEquals("own", GET("/things/" + id));
                }
            }
        """.trimIndent())

        TestProject.runner(app, "test").build()

        assertPassed(app, "NoneTest", 6)
        assertPassed(app, "OpensItsOwnTest", 1)
    }

    @Test
    fun `the JVM count is play1 testJvms, which -PtestJvms overrides`(@TempDir tmp: File) {
        TestProject.write(tmp)
        File(tmp, "build.gradle.kts").appendText("""
            tasks.register("printTestJvms") {
                val test = tasks.named<Test>("test")
                doLast { println("TEST_JVMS=" + test.get().maxParallelForks) }
            }
        """.trimIndent() + "\n")
        fun jvms(vararg args: String) = Regex("TEST_JVMS=(\\d+)").find(TestProject.runner(tmp, "printTestJvms", *args).build().output)!!.groupValues[1].toInt()

        assertEquals(DEFAULT_TEST_JVMS, jvms())
        File(tmp, "build.gradle.kts").appendText("play1 { testJvms = 3 }\n")
        assertEquals(3, jvms())
        assertEquals(4, jvms("-PtestJvms=4"))
        val refused = TestProject.runner(tmp, "printTestJvms", "-PtestJvms=0").buildAndFail()
        assertTrue("testJvms must be 1 or more, not 0" in refused.output, refused.output)
    }

    private fun staticTest(name: String) = """
        import java.net.URI;
        import java.net.http.*;
        import play.Play;
        public class Static${name}Test extends play.test.UnitTest {
            @org.junit.jupiter.api.Test
            public void ownsTheStaticAndItsServer() throws Exception {
                assertNull(utils.Shared.owner, "another class set it in this JVM");
                utils.Shared.owner = "$name";
                String port = Play.configuration.getProperty("http.port");
                HttpResponse<String> index = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals("ok", index.body());
                System.out.println("PF230 pid=" + ProcessHandle.current().pid() + " port=" + port
                    + " jvm=" + System.getProperty("play.test.jvm"));
                Thread.sleep(500);
                assertEquals("$name", utils.Shared.owner);
            }
        }
    """.trimIndent()

    private data class Jvm(val pid: String, val port: Int, val number: String)

    private fun jvmOf(app: File, testClass: String): Jvm {
        val report = report(app, testClass)
        assertTrue("""failures="0" errors="0"""" in report, report)
        val (pid, port, number) = Regex("""PF230 pid=(\d+) port=(\d+) jvm=(\d+)""").find(report)?.destructured
            ?: error("$testClass reported no JVM:\n$report")
        return Jvm(pid, port.toInt(), number)
    }

    private fun assertPassed(app: File, testClass: String, tests: Int) {
        val report = report(app, testClass)
        assertTrue("""tests="$tests" skipped="0" failures="0" errors="0"""" in report, report)
    }

    private fun reports(app: File) = File(app, "build/test-results/test").list { _, name -> name.endsWith(".xml") }!!.sorted()

    private fun report(app: File, testClass: String): String {
        val xml = File(app, "build/test-results/test/TEST-$testClass.xml")
        assertTrue(xml.isFile, "no JUnit XML for $testClass: ${reports(app)}")
        return xml.readText()
    }

    /** An application on this checkout's framework: one model, three actions, the %test database in memory. */
    private fun app(tmp: File, conf: String): File {
        assumeTrue(File(repo, "framework/play-$version.jar").isFile, "ant jar builds framework/play-$version.jar")
        val app = File(tmp, "app").apply { mkdirs() }
        TestProject.write(
            app,
            play1Block = """
                frameworkVersion.set("$version")
                frameworkPath.set(file("${repo.absolutePath.replace("\\", "\\\\")}"))
            """.trimIndent()
        )
        write(app, "conf/application.conf", "application.name=pf230\napplication.mode=dev\n" +
            "application.secret=\${PF230_SECRET}\n%test.db.url=jdbc:h2:mem:pf230\n%test.jpa.ddl=create\n$conf")
        write(app, "conf/routes", "GET / Application.index\nGET /things Application.list\nGET /things/{id} Application.show\n")
        write(app, "app/models/Thing.java", """
            package models;
            @jakarta.persistence.Entity
            public class Thing extends play.db.jpa.Model {
                public String name;
            }
        """.trimIndent())
        write(app, "app/utils/Shared.java", "package utils;\npublic class Shared {\n    public static String owner;\n}\n")
        write(app, "app/controllers/Application.java", """
            package controllers;
            import java.util.List;
            import models.Thing;
            public class Application extends play.mvc.Controller {
                public static void index() {
                    renderText("ok");
                }
                public static void list() {
                    List<Thing> things = Thing.findAll();
                    StringBuilder text = new StringBuilder().append(things.size());
                    for (Thing thing : things) {
                        text.append(' ').append(thing.name);
                    }
                    renderText(text);
                }
                public static void show(Long id) {
                    Thing thing = Thing.findById(id);
                    notFoundIfNull(thing);
                    renderText(thing.name);
                }
            }
        """.trimIndent())
        return app
    }

    private fun write(app: File, path: String, content: String) {
        File(app, path).apply { parentFile.mkdirs() }.writeText(content + "\n")
    }
}
