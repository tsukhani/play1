package play.gradle

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.TestLauncher
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestSuccessResult
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
            // The server binds before the application starts, as Server.main binds it, so start-time
            // code sees the port the JVM listens on.
            write(app, "app/jobs/PortAtStart.java", """
                package jobs;
                @play.jobs.OnApplicationStart
                public class PortAtStart extends play.jobs.Job<Void> {
                    public static String port;
                    @Override
                    public void doJob() {
                        port = play.Play.configuration.getProperty("http.port");
                    }
                }
            """.trimIndent())
            // A status a test leaves set is JUnit's to clear when the test ends, as under /@tests,
            // and no reason to end the class's job.
            write(app, "test/InterruptTest.java", """
                import org.junit.jupiter.api.*;
                @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
                public class InterruptTest extends play.test.UnitTest {
                    @BeforeEach
                    public void setUp(TestInfo test) {
                        if (test.getTestMethod().get().getName().equals("seesWhatItsBeforeEachLeft")) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    @Test @Order(1)
                    public void leavesItsThreadInterrupted() {
                        Thread.currentThread().interrupt();
                    }
                    @Test @Order(2)
                    public void theNextTestRunsWithTheStatusClear() throws Exception {
                        assertFalse(Thread.currentThread().isInterrupted());
                        Thread.sleep(10);
                    }
                    @Test @Order(3)
                    public void seesWhatItsBeforeEachLeft() {
                        assertTrue(Thread.interrupted());
                    }
                    @Test @Order(4)
                    public void runsInTheClassJob() {
                        assertTrue(play.db.jpa.JPA.isInsideTransaction());
                    }
                }
            """.trimIndent())
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
            assertPassed(app, "InterruptTest", 4)

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
    fun `an IDE runs one method or one class through the Tooling API`(@TempDir tmp: File) {
        // IntelliJ IDEA and Eclipse run an application's tests through Gradle's Tooling API, whose
        // TestLauncher runs the tests it names through the test task and reports each one as an
        // event, which the IDE shows.
        val app = app(tmp, conf = "")
        write(app, "test/FirstTest.java", """
            public class FirstTest extends play.test.UnitTest {
                @org.junit.jupiter.api.Test public void a() {}
                @org.junit.jupiter.api.Test public void b() {
                    assertTrue(play.Play.started);
                }
            }
        """.trimIndent())
        write(app, "test/SecondTest.java", """
            public class SecondTest extends play.test.UnitTest {
                @org.junit.jupiter.api.Test public void c() {
                    assertEquals(1, 2, "pf230 from the IDE");
                }
            }
        """.trimIndent())
        // TestKit injects the plugin under test into the builds it runs; this build has it on the
        // settings' buildscript classpath instead, which the build script's plugins block sees.
        val pluginClasspath = java.util.Properties().apply {
            TestJvmsTest::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use { load(it) }
        }.getProperty("implementation-classpath").split(File.pathSeparator)
        val settings = File(app, "settings.gradle.kts")
        settings.writeText("buildscript { dependencies { classpath(files(" +
            pluginClasspath.joinToString { "\"" + it.replace("\\", "\\\\") + "\"" } + ")) } }\n" + settings.readText())
        // The daemon this starts, beside TestKit's, does not outlive the suite for long.
        write(app, "gradle.properties", "org.gradle.daemon.idletimeout=60000")

        fun launch(tests: TestLauncher.() -> Unit): Map<String, String> {
            val outcomes = sortedMapOf<String, String>()
            GradleConnector.newConnector()
                .useInstallation(File(System.getProperty("play.test.gradleHome")))
                // TestKit's own, so that the build reuses its caches
                .useGradleUserHomeDir(File(System.getProperty("java.io.tmpdir"), ".gradle-test-kit-" + System.getProperty("user.name")))
                .forProjectDirectory(app)
                .connect().use { connection ->
                    val launcher = connection.newTestLauncher()
                    launcher.tests()
                    launcher.addProgressListener(ProgressListener { event ->
                        val test = event.descriptor as? JvmTestOperationDescriptor
                        if (event is TestFinishEvent && test?.methodName != null) {
                            outcomes["${test.className}.${test.methodName}"] = when (event.result) {
                                is TestSuccessResult -> "passed"
                                is TestFailureResult -> "failed: " + (event.result as TestFailureResult).failures.joinToString { it.message.orEmpty() }
                                else -> "skipped"
                            }
                        }
                    }, OperationType.TEST)
                    launcher.setStandardOutput(System.out).setStandardError(System.err)
                    runCatching { launcher.run() }
                }
            return outcomes
        }

        assertEquals(mapOf("FirstTest.b()" to "passed"), launch { withTaskAndTestMethods(":test", "FirstTest", listOf("b")) })
        val second = launch { withTaskAndTestClasses(":test", listOf("SecondTest")) }
        assertEquals(setOf("SecondTest.c()"), second.keys)
        assertTrue("pf230 from the IDE ==> expected: <1> but was: <2>" in second.getValue("SecondTest.c()"), second.toString())
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
        val build = File(tmp, "build.gradle.kts")
        // An eager withType above the play1 block realizes the test task before the block has run.
        build.writeText(build.readText().replace("play1 {", "tasks.withType<Test> { systemProperty(\"pf230.eager\", \"1\") }\n\nplay1 {"))
        build.appendText("""
            tasks.register("printTestJvms") {
                val test = tasks.named<Test>("test")
                doLast { println("TEST_JVMS=" + test.get().maxParallelForks + " TEST_HEAP=" + test.get().maxHeapSize) }
            }
        """.trimIndent() + "\n")
        fun printed(vararg args: String) = Regex("TEST_JVMS=(\\d+) TEST_HEAP=(\\S+)").find(TestProject.runner(tmp, "printTestJvms", *args).build().output)!!.groupValues
        fun jvms(vararg args: String) = printed(*args)[1].toInt()

        assertEquals(DEFAULT_TEST_JVMS, jvms())
        build.appendText("play1 { testJvms = 3 }\n")
        assertEquals(3, jvms())
        assertEquals(4, jvms("-PtestJvms=4"))
        val refused = TestProject.runner(tmp, "printTestJvms", "-PtestJvms=0").buildAndFail()
        assertTrue("testJvms must be 1 or more, not 0" in refused.output, refused.output)

        // The heap jvm.memory's flags give, which Gradle's own -Xmx, after them, would replace.
        write(tmp, "conf/application.conf", "%test.jvm.memory=-XX:MaxRAM=8g -XX:MaxRAMPercentage=10\n")
        assertEquals("819m", printed()[2])
        write(tmp, "conf/application.conf", "%test.jvm.memory=-Xms256m -Xmx1500m\n")
        assertEquals("1500m", printed()[2])
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
                assertEquals(port, jobs.PortAtStart.port, "the port an @OnApplicationStart job saw");
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
