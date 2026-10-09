package play.gradle

import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

// Same reservation as JulBridgeArgsTest: the forked JVMs and the detached playStart child are
// unverified on Windows, and playAotGen refuses there.
private const val FORKS_JVMS = "forks detached JVMs from TestKit, and aot-gen refuses on Windows"

/**
 * PF-185: an application started through Gradle with -Dprecompiled=true -- from its working
 * copy or from a `play dist` install -- starts from the JDK's AOT cache once it has one, as a
 * bundle does. The JVM refuses to write a cache while a non-empty directory is on the
 * classpath, and a Gradle start has three: conf/, Gradle's compile output and its copy of
 * conf/. So the training run and every start that is given the cache run on a classpath of
 * files only. Nothing else changes: an application that has no cache, and any start that
 * compiles, is launched as it always was.
 *
 * `playAotGen` is the training run that writes the cache, build/play/aot/app.aot: under
 * Gradle's build directory, which an application's .gitignore already excludes. playRun,
 * playStart and playRestart use it. BundleLauncherTest covers the same command of the bundle
 * launcher, and holds the two to one rule for leaving -XX:AOTCache out.
 *
 * Every task runs for real against a fake framework whose play.server.Server reports what
 * the JVM was started with, or plays the application a training run needs.
 */
class PrecompiledStartTest {

    private val fwVersion = "9.9.9-TEST"
    private val julManager = "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager"
    private val precompiled = "-PjvmArgs=-Dprecompiled=true"

    // ---- the classpath -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playStart", "playRestart"])
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `a start from the cache has only files on its JVM classpath, and any other start is launched as before`(
        task: String, @TempDir tmp: File
    ) {
        val app = writeApp(tmp)

        // A start that compiles: conf/ first and Gradle's compile output behind it, which
        // javassist reads while the application's classes are enhanced (PF-94).
        val compiling = report(app, task).classpath
        assertEquals(File(app, "conf").canonicalFile, compiling.first().canonicalFile)
        assertTrue(
            File(app, "build/classes/java/main").canonicalFile in compiling.map { it.canonicalFile },
            "$compiling"
        )

        // Precompiled, with no cache to start from: nothing changes for an application
        // that never ran aot-gen.
        assertEquals(compiling, report(app, task, precompiled).classpath)

        // With a cache: the same entries in the same order, without the directories.
        cacheOf(app).writeText("a cache, for all the plugin can tell")
        val fromCache = report(app, task, precompiled).classpath
        assertEquals(emptyList<File>(), fromCache.filter { it.isDirectory })
        assertEquals(compiling.filter { it.isFile }, fromCache)
        assertEquals(
            listOf("play-$fwVersion.jar", "extra.jar"),
            fromCache.map { it.name },
            "the framework and the application's own jar stay"
        )

        // A cache is no reason to change a start that compiles, which cannot use it.
        assertEquals(compiling, report(app, task).classpath)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `what the JVM is given decides whether a start is precompiled`(@TempDir tmp: File) {
        // Server.main takes -Dprecompiled=true and nothing else for it, and the JVM keeps the
        // last -D of a key: application.conf first, then the command line.
        // With a cache in place, the classpath shows which way a start was taken.
        val app = writeApp(tmp, conf = "jvm.memory=-Xmx64m -Dprecompiled=true\n")
        cacheOf(app).writeText("a cache, for all the plugin can tell")
        fun directories(task: String, vararg args: String) = report(app, task, *args).classpath.filter { it.isDirectory }

        assertEquals(emptyList<File>(), directories("playStart"))
        assertEquals(emptyList<File>(), directories("playRun"))
        assertTrue(directories("playStart", "-PjvmArgs=-Dprecompiled=false").isNotEmpty())
        assertTrue(directories("playRun", "-PjvmArgs=-Dprecompiled=false").isNotEmpty())

        // playRun is a JavaExec, which a build script can add to; that counts as well.
        File(app, "conf/application.conf").writeText("application.name=testapp\n")
        assertTrue(directories("playRun").isNotEmpty())
        File(app, "build.gradle.kts").appendText(
            """
            tasks.named<JavaExec>("playRun") {
                systemProperty("precompiled", "true")
            }
            """.trimIndent() + "\n"
        )
        assertEquals(emptyList<File>(), directories("playRun"))
    }

    // ---- using the cache ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["playRun", "playStart"])
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `a precompiled start uses the AOT cache when the application has one`(task: String, @TempDir tmp: File) {
        // -XX options, which reach the JVM where they were put: a JavaExec moves a -D or an
        // -Xmx it is given to a place of its own on the command line.
        val app = writeApp(tmp, conf = "jvm.memory=-XX:+UseSerialGC\n")
        fun aotFlags(vararg args: String) = report(app, task, *args).jvmArgs.filter { it.startsWith("-XX:AOT") }

        assertEquals(emptyList<String>(), aotFlags(precompiled), "there is no cache yet")

        // Not a cache at all, which the JVM answers with a few [aot] lines and a normal start.
        val cache = cacheOf(app).apply { writeText("a cache, for all the plugin can tell") }
        val run = report(app, task, "-PjvmArgs=-Dprecompiled=true -XX:MaxHeapFreeRatio=60")
        val option = run.jvmArgs.single { it.startsWith("-XX:AOTCache=") }
        assertEquals(cache.canonicalFile, File(option.substringAfter('=')).canonicalFile)
        // Ahead of everything application.conf and the command line supply: the JVM keeps
        // the last value it is given for an option.
        assertTrue(run.jvmArgs.indexOf(option) < run.jvmArgs.indexOf("-XX:+UseSerialGC"), "${run.jvmArgs}")
        assertTrue(run.jvmArgs.indexOf("-XX:+UseSerialGC") < run.jvmArgs.indexOf("-XX:MaxHeapFreeRatio=60"), "${run.jvmArgs}")

        // A start that compiles has directories on its classpath, and no cache fits those.
        assertEquals(emptyList<String>(), aotFlags())

        // The operator's own option stays beside it where the JVM lets the later one decide,
        // and replaces it where the JVM would refuse to start with both.
        assertEquals(listOf(option, "-XX:AOTMode=off"), aotFlags("-PjvmArgs=-Dprecompiled=true -XX:AOTMode=off"))
        val refused = report(app, task, "-PjvmArgs=-Dprecompiled=true -Xshare:auto")
        assertEquals(emptyList<String>(), refused.jvmArgs.filter { it.startsWith("-XX:AOT") })
        // ...and a start that is not given the cache keeps the classpath it always had.
        assertTrue(refused.classpath.any { it.isDirectory }, "${refused.classpath}")
    }

    // ---- aot-gen -----------------------------------------------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen starts the JVM with the options of a precompiled start plus the cache output flag`(@TempDir tmp: File) {
        val app = writeApp(tmp, conf = "jvm.memory=-Xmx64m\n%staging.jvm.memory=-Xmx96m\n")
        val args = arrayOf(
            "-PplayId=staging", "-PhttpPort=${freePort()}", "-PhttpsPort=${freePort()}",
            "-PjvmArgs=-Dprecompiled=true -Dpf185=yes -XX:+UseSerialGC"
        )
        // The start a cache is for is one that is given the cache.
        cacheOf(app).writeText("the cache in use")
        val start = report(app, "playStart", *args)
        val use = start.jvmArgs.single { it.startsWith("-XX:AOTCache=") }
        assertTrue(start.jvmArgs.indexOf(use) < start.jvmArgs.indexOf(julManager), "${start.jvmArgs}")

        // The fake Server reports and returns, which aot-gen takes for a failed start; its
        // command line is the subject here. A cache trained under other options -- another
        // collector, another classpath -- is one the JVM rejects at the next start.
        val training = trainingReport(app, *args)
        val output = training.jvmArgs.single { it.startsWith("-XX:AOTCacheOutput=") }
        assertEquals(File(app, "build/play/aot/app.aot.new").canonicalFile, File(output.substringAfter('=')).canonicalFile)
        // The cache being replaced is not an input to its own replacement, and the JVM
        // refuses -XX:AOTCache next to -XX:AOTCacheOutput: the one takes the place of the other.
        assertEquals(start.jvmArgs.map { if (it == use) output else it }, training.jvmArgs)
        assertEquals(start.classpath, training.classpath)
        assertEquals(start.args, training.args)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen trains a precompiled start whatever its arguments say`(@TempDir tmp: File) {
        // A cache serves precompiled starts only, so that is what the training run is, also
        // when -Dprecompiled=true was left off its command line.
        val app = writeApp(tmp)

        val training = trainingReport(app, "-PhttpPort=${freePort()}", "-PjvmArgs=-Xmx64m")

        assertEquals(listOf("-Dprecompiled=true"), training.jvmArgs.filter { it.startsWith("-Dprecompiled") })
        assertEquals(emptyList<File>(), training.classpath.filter { it.isDirectory })
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen takes the port from the command line, then from application conf`(@TempDir tmp: File) {
        val (bare, staging, given) = Triple(freePort(), freePort(), freePort())
        val app = writeApp(tmp, conf = "http.port=$bare\n%staging.http.port=$staging\n")
        fun ports(vararg args: String) = trainingReport(app, *args).args.filter { it.startsWith("--http.port=") }

        // The port it waits on is the port it tells the application to use, so the two
        // cannot differ however application.conf is put together.
        assertEquals(listOf("--http.port=$bare"), ports())
        assertEquals(listOf("--http.port=$staging"), ports("-PplayId=staging"))
        assertEquals(listOf("--http.port=$given"), ports("-PhttpPort=$given"))

        // A value that cannot be read as a port is not guessed at.
        File(app, "conf/application.conf").writeText("http.port=\${HTTP_PORT}\n")
        val unreadable = aotGenFails(app)
        assertTrue("--http.port=<port>" in unreadable.output, unreadable.output)
        assertFalse(File(app, "logs/aot-gen.out").exists(), "the JVM must not have been started")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen refuses a port that is in use and starts nothing`(@TempDir tmp: File) {
        val app = appInUse(tmp)

        val training = ServerSocket(0).use { taken -> aotGenFails(app, "-PhttpPort=${taken.localPort}") }

        assertTrue("already in use" in training.output, training.output)
        assertFalse(File(app, "logs/aot-gen.out").exists(), "the JVM must not have been started")
        assertLeftAsItWas(app)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen wants precompiled classes and never precompiles itself`(@TempDir tmp: File) {
        // Precompiling deletes precompiled/ under an instance that may be running from it.
        val app = appInUse(tmp)
        File(app, "precompiled").deleteRecursively()

        val training = aotGenFails(app, "-PhttpPort=${freePort()}")

        assertTrue("play precompile" in training.output, training.output)
        assertFalse(File(app, "precompiled").exists(), "nothing may have been precompiled")
        assertFalse(File(app, "logs/aot-gen.out").exists(), "the JVM must not have been started")
        assertLeftAsItWas(app)

        // A `play dist` install has its classes as one jar, and that will do.
        File(app, "precompiled").mkdirs()
        File(app, "precompiled/classes.jar").writeText("")
        assertTrue("stopped before" in aotGenFails(app, "-PhttpPort=${freePort()}").output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen reports an application that fails to start and leaves no partial cache`(@TempDir tmp: File) {
        val app = appInUse(tmp)

        val training = aotGenFails(app, "-PhttpPort=${freePort()}", "-PjvmArgs=-Dpf185.mode=refuse")

        assertTrue("stopped before" in training.output, training.output)
        // Why it stopped is in the application's own output: the tail of it is shown.
        assertTrue("Could not bind on port" in training.output, training.output)
        assertLeftAsItWas(app)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen gives up on an application that never listens and stops it`(@TempDir tmp: File) {
        val app = appInUse(tmp)
        val args = arrayOf("-PhttpPort=${freePort()}", "-PjvmArgs=-Dpf185.mode=hang")

        val unreadable = aotGenFails(app, *args, env = mapOf("PLAY_AOT_TIMEOUT" to "soon"))
        assertTrue("PLAY_AOT_TIMEOUT is a number of seconds, not 'soon'" in unreadable.output, unreadable.output)
        assertFalse(File(app, "logs/aot-gen.out").exists(), "the JVM must not have been started")

        val training = aotGenFails(app, *args, env = mapOf("PLAY_AOT_TIMEOUT" to "2"))
        assertTrue("after 2s" in training.output, training.output)
        // Up, but never listening. Its pid shows whether aot-gen stopped it.
        val pid = File(app, "training.pid").readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the training run (pid $pid) was left running")
        assertLeftAsItWas(app)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen does not take a cache the JVM did not finish assembling`(@TempDir tmp: File) {
        // The JVM assembles the cache in a child process of its own. When that child dies
        // part way -- out of memory in a container, say -- the JVM still exits as it does for
        // any SIGTERM, but leaves what was written of the cache and, beside it, the recording
        // it removes once a cache is complete (seen on 25.0.2 by killing the child). The
        // fake Server leaves exactly that behind.
        val app = appInUse(tmp)

        val training = aotGenFails(app, "-PhttpPort=${freePort()}", "-PjvmArgs=-Dpf185.mode=truncate")

        val jvmOutput = File(app, "logs/aot-gen.out").readLines()
        assertTrue("REQUEST GET / HTTP/1.1" in jvmOutput, "the run never got as far as stopping:\n$jvmOutput")
        assertTrue("without completing the cache" in training.output, training.output)
        // The JVM's own word on why, which is an [aot] line like its warnings.
        assertTrue("Child process failed" in training.output, training.output)
        assertLeftAsItWas(app)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = FORKS_JVMS)
    fun `aot-gen trains a real JVM and a precompiled start then runs from the cache it wrote`(@TempDir tmp: File) {
        assumeTrue(Runtime.version().feature() >= 25, "-XX:AOTCacheOutput needs JDK 25")
        val app = writeApp(tmp, conf = "jvm.memory=-Xmx256m\n")
        File(app, "server.pid").writeText("4242")

        val training = TestProject.runner(app, "playAotGen", "-PhttpPort=${freePort()}", "-PjvmArgs=-Dpf185.mode=listen").build()

        val jvmOutput = File(app, "logs/aot-gen.out").readLines()
        assertTrue("REQUEST GET / HTTP/1.1" in jvmOutput, "no request was served:\n$jvmOutput")
        // The JVM writes the cache on its way out of a normal exit, hooks included.
        assertTrue("SHUTDOWN HOOK" in jvmOutput, "the JVM was not stopped gracefully:\n$jvmOutput")
        val cache = cacheOf(app)
        assertTrue(cache.length() > 1_000_000, "app.aot is ${cache.length()} bytes: not a cache")
        assertEquals(listOf("app.aot"), cache.parentFile.list()!!.toList(), "leftovers of the training run")
        assertEquals("4242", File(app, "server.pid").readText(), "aot-gen touched a running instance's pid file")
        // Where it is and how big.
        val where = training.output.lines().single { "the AOT cache is at" in it }
        assertEquals(cache.canonicalFile, File(where.substringAfter(": ").substringBefore(" (")).canonicalFile, where)
        assertTrue(Regex("""\(\d+ MB\)$""").containsMatchIn(where), where)

        // Under Gradle's build directory and nowhere else: nothing new for git to list.
        assertEquals(
            setOf(".gradle", "app", "build", "build.gradle.kts", "conf", "lib", "logs", "modules", "precompiled", "server.pid", "settings.gradle.kts"),
            app.list()!!.toSet()
        )

        // -XX:AOTMode=on makes the JVM refuse to start unless it can use the cache it is
        // given, so a start that gets as far as reporting is the JVM's own word that the
        // cache aot-gen wrote fits the options each of these tasks starts it with.
        File(app, "server.pid").delete()
        for (task in listOf("playRun", "playStart")) {
            val start = report(app, task, "-PjvmArgs=-Dprecompiled=true -XX:AOTMode=on")
            assertTrue(start.jvmArgs.any { it.startsWith("-XX:AOTCache=") }, "$task: ${start.jvmArgs}")
            assertEquals(emptyList<File>(), start.classpath.filter { it.isDirectory }, task)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `aot-gen refuses on Windows, where a JVM cannot be stopped gracefully`(@TempDir tmp: File) {
        val app = appInUse(tmp)

        val training = aotGenFails(app, "-PhttpPort=${freePort()}")

        assertTrue("not available on Windows" in training.output, training.output)
        assertFalse(File(app, "logs/aot-gen.out").exists(), "the JVM must not have been started")
        assertLeftAsItWas(app)
    }

    // ---- helpers -----------------------------------------------------------------------

    /** What a JVM of the fake framework was started with. */
    private class Report(output: String) {
        private val lines = output.lines().map { it.trimEnd('\r') }
        val classpath: List<File> = lines.single { it.startsWith("CLASSPATH=") }.substringAfter('=')
            .split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
        val jvmArgs: List<String> = lines.filter { it.startsWith("JVMARG=") }.map { it.substringAfter('=') }
        val args: List<String> = lines.filter { it.startsWith("ARG=") }.map { it.substringAfter('=') }
    }

    /** Run the start [task] and return what its JVM reported before it returned. */
    private fun report(app: File, task: String, vararg args: String): Report {
        val pidFile = File(app, "server.pid").apply { delete() }
        val systemOut = File(app, "logs/system.out").apply { delete() }
        val result = TestProject.runner(app, task, *args).build()
        // playRun forwards the JVM's stdout into the build output; the other two redirect it
        // to logs/system.out and return before their detached child has written.
        if (task == "playRun") return Report(result.output)
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline && !(systemOut.isFile && "REPORT DONE" in systemOut.readText())) {
            Thread.sleep(50)
        }
        val output = if (systemOut.isFile) systemOut.readText() else ""
        if ("REPORT DONE" !in output) fail<Unit>("$task's JVM never reported:\n$output")
        // The child is gone before the next start looks at its pid file.
        ProcessHandle.of(pidFile.readText().trim().toLong()).ifPresent { it.onExit().get(30, TimeUnit.SECONDS) }
        return Report(output)
    }

    private fun aotGenFails(app: File, vararg args: String, env: Map<String, String> = emptyMap()): BuildResult {
        File(app, "logs/aot-gen.out").delete()
        val runner = TestProject.runner(app, "playAotGen", *args)
        // withEnvironment replaces the build's whole environment, so start from this one.
        if (env.isNotEmpty()) runner.withEnvironment(System.getenv() + env)
        return runner.buildAndFail()
    }

    /** One `playAotGen` against the reporting Server, which ends as a failed start, and what its JVM was started with. */
    private fun trainingReport(app: File, vararg args: String): Report {
        val training = aotGenFails(app, *args)
        assertTrue("stopped before it was listening" in training.output, training.output)
        return Report(File(app, "logs/aot-gen.out").readText())
    }

    /** What must be true after any `aot-gen` that failed: nothing new, nothing touched. */
    private fun assertLeftAsItWas(app: File) {
        assertEquals("4242", File(app, "server.pid").readText(), "aot-gen touched a running instance's pid file")
        assertEquals("the cache in use", cacheOf(app).readText(), "aot-gen damaged the cache in use")
        assertEquals(listOf("app.aot"), cacheOf(app).parentFile.list()!!.toList(), "a half-written cache was left behind")
    }

    /** An application that already has a cache and the pid file of an instance playStart is tracking. */
    private fun appInUse(tmp: File): File = writeApp(tmp).also { app ->
        File(app, "server.pid").writeText("4242")
        cacheOf(app).writeText("the cache in use")
    }

    /** Where playAotGen keeps the application's cache; its directory is there on return. */
    private fun cacheOf(app: File): File = File(app, "build/play/aot/app.aot").apply { parentFile.mkdirs() }

    /** A port nothing listens on: handed out by the OS and released again. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * An application as `play precompile` leaves it: a source for Gradle to compile, so that
     * its compile output is a directory with something in it, a jar of its own in lib/, and
     * a precompiled/java tree.
     */
    private fun writeApp(tmp: File, conf: String = "application.name=testapp\n"): File {
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
        File(app, "conf/application.conf").writeText(conf)
        File(app, "app").mkdirs()
        File(app, "app/Marker.java").writeText("public class Marker {}\n")
        File(app, "precompiled/java").mkdirs()
        File(app, "lib").mkdirs()
        JarOutputStream(File(app, "lib/extra.jar").outputStream(), Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }).close()
        return app
    }

    /**
     * A framework distribution just real enough to launch: a play jar that is a loadable
     * -javaagent and holds a play.server.Server which, by -Dpf185.mode,
     *  - reports its classpath, JVM arguments and arguments and returns (the default);
     *  - `listen`: listens on --http.port and answers every request with 200 until the JVM is
     *    told to stop, which is what a training run needs of an application;
     *  - `truncate`: the same, but leaves on its way out what a JVM leaves whose cache
     *    assembly died, and then kills itself: a JVM that gets as far as exiting, through
     *    Runtime.halt even, writes a complete cache over it;
     *  - `refuse`: exits with status 1 before it listens;
     *  - `hang`: writes its pid to training.pid and never listens.
     */
    private fun fakeFramework(root: File): File {
        val source = File(root.parentFile, "fake-src/play/server/Server.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package play.server;
                import java.io.BufferedReader;
                import java.io.IOException;
                import java.io.InputStreamReader;
                import java.lang.management.ManagementFactory;
                import java.net.ServerSocket;
                import java.net.Socket;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;
                public class Server {
                    public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
                    public static void main(String[] args) throws Exception {
                        List<String> jvm = ManagementFactory.getRuntimeMXBean().getInputArguments();
                        String mode = System.getProperty("pf185.mode", "report");
                        switch (mode) {
                            case "report" -> {
                                System.out.println("CLASSPATH=" + System.getProperty("java.class.path"));
                                for (String arg : jvm) System.out.println("JVMARG=" + arg);
                                for (String arg : args) System.out.println("ARG=" + arg);
                                System.out.println("REPORT DONE");
                            }
                            case "refuse" -> {
                                System.out.println("Could not bind on port");
                                System.exit(1);
                            }
                            case "hang" -> {
                                Files.writeString(Path.of("training.pid"), Long.toString(ProcessHandle.current().pid()));
                                Thread.sleep(60_000);
                            }
                            default -> listen(args, jvm, mode.equals("truncate"));
                        }
                    }
                    private static void listen(String[] args, List<String> jvm, boolean truncate) throws IOException {
                        int port = 0;
                        for (String arg : args) {
                            if (arg.startsWith("--http.port=")) port = Integer.parseInt(arg.substring("--http.port=".length()));
                        }
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            System.out.println("SHUTDOWN HOOK");
                            if (!truncate) return;
                            for (String arg : jvm) {
                                if (!arg.startsWith("-XX:AOTCacheOutput=")) continue;
                                String out = arg.substring("-XX:AOTCacheOutput=".length());
                                try {
                                    Files.writeString(Path.of(out), "partial");
                                    Files.writeString(Path.of(out + ".config"), "recording");
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            System.out.println("[9.9s][error  ][aot] Child process failed; status = 137");
                            System.out.flush();
                            try {
                                new ProcessBuilder("kill", "-9", Long.toString(ProcessHandle.current().pid())).start().waitFor();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }));
                        try (ServerSocket server = new ServerSocket(port)) {
                            while (true) {
                                try (Socket socket = server.accept()) {
                                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                                    String request = in.readLine();
                                    // null: a connection opened only to see whether the port answers.
                                    if (request == null) continue;
                                    for (String header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {}
                                    System.out.println("REQUEST " + request);
                                    socket.getOutputStream().write(
                                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes());
                                } catch (IOException e) {
                                    // a client that hung up; keep listening
                                }
                            }
                        }
                    }
                }
                """.trimIndent()
            )
        }
        val classes = File(root.parentFile, "fake-classes").apply { mkdirs() }
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK, not a JRE")
        assertEquals(
            0, javac.run(null, null, null, "--release", "25", "-d", classes.absolutePath, source.absolutePath),
            "compiling the fake framework failed"
        )
        File(root, "framework/lib").mkdirs()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Premain-Class")] = "play.server.Server"
        }
        JarOutputStream(File(root, "framework/play-$fwVersion.jar").outputStream(), manifest).use { jar ->
            classes.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(classes).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return root
    }
}
