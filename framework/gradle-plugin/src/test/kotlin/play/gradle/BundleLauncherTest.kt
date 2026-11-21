package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/**
 * PF-171: the bundled `play` launcher (src/main/resources/bundle-play.sh, baked
 * into the zip by PlayBundleTask) has to hand a *native* JVM its arguments even
 * when the shell running it is POSIX — Git Bash / MSYS2 / Cygwin on Windows.
 * java.exe splits -classpath on ';' rather than ':' and cannot resolve
 * '/c/Users/...', so a POSIX-only launcher dies with a NoClassDefFoundError on
 * the first framework dependency (the -javaagent it had until PF-239 kept the
 * framework jar itself loadable, which is why the symptom looked like a packaging bug).
 *
 * Most tests run the real script and assert the argv it hands `java` — a stub
 * that just echoes its arguments. On macOS and Linux the MSYS branch is reached
 * by stubbing `uname` and `cygpath` on PATH. On Windows the whole class runs the
 * script through Git Bash, the shell an installed bundle is started with there,
 * so `uname`, `cygpath` and bash itself are the real ones (PF-183): before that
 * it was skipped on Windows, and a launcher that broke only there went unseen.
 */
class BundleLauncherTest {

    companion object {
        /**
         * PF-227: the bash the launcher runs under off Windows, which the build passes from
         * -PlauncherBash, /bin/bash by default. Before, the first bash on the PATH ran it: bash 5
         * on Linux, or on a Mac with a newer bash first, though a stock Mac runs a bundle under
         * bash 3.2.57. bash32/test.sh runs this class again under a bash 3.2.57 built for it.
         */
        private val launcherBash: String = System.getProperty("play.test.launcherBash") ?: "/bin/bash"

        /** A directory holding only `bash`, a link to [launcherBash], for [launcherBashFirst]. */
        private val launcherBashDir: File by lazy {
            Files.createTempDirectory("launcher-bash").toFile().also { dir ->
                dir.deleteOnExit()
                val link = File(dir, "bash")
                Files.createSymbolicLink(link.toPath(), Path.of(launcherBash))
                link.deleteOnExit()
            }
        }
    }

    private val fwVersion = "1.13.57"
    private val onWindows = OS.WINDOWS.isCurrentOs

    /** What the launcher joins -classpath with on the machine the tests run on. */
    private val cpSep = if (onWindows) ";" else ":"

    /**
     * Git Bash's bash.exe. Nothing on Windows executes a #! script directly, and
     * this is the bash install.ps1-style installers start a bundle with. The
     * bin\ wrapper rather than usr\bin\bash.exe: it is what puts the MSYS tools
     * on PATH. Not `bash` from PATH either, which on a stock Windows is the WSL
     * launcher in System32 -- a different machine as far as the bundle goes.
     */
    private val gitBash: String? by lazy {
        (listOf("ProgramFiles", "ProgramFiles(x86)").mapNotNull { System.getenv(it) }.map { "$it\\Git" } +
            listOfNotNull(System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Git" }))
            .map { "$it\\bin\\bash.exe" }
            .firstOrNull { File(it).isFile }
    }

    @BeforeEach
    fun needsGitBashOnWindows() {
        if (!onWindows) return
        // On CI a missing Git Bash has to fail: a skip is how these went unrun on Windows.
        if (System.getenv("CI") != null) assertNotNull(gitBash, "Git Bash (Git\\bin\\bash.exe) was not found")
        assumeTrue(gitBash != null, "the bundle launcher needs Git Bash on Windows")
    }

    /**
     * Put [dirs] ahead of the inherited PATH, under the name the variable already
     * has. On Windows that is `Path` and ProcessBuilder's map is case-sensitive,
     * so setting "PATH" hands the child both -- and Git Bash keeps the original,
     * which sent every stub-java test to the real java.exe.
     */
    private fun ProcessBuilder.pathFirst(vararg dirs: File): ProcessBuilder = apply {
        val name = environment().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        environment()[name] =
            (dirs.map { it.absolutePath } + listOfNotNull(environment()[name])).joinToString(File.pathSeparator)
    }

    /** The command line that runs the bundle's `play` with [args]. */
    private fun play(vararg args: String): List<String> =
        if (onWindows) listOf(gitBash!!, "./play", *args) else listOf("./play", *args)

    /**
     * PF-227: put [launcherBash] ahead of the PATH, so that `./play`, which starts through
     * `#!/usr/bin/env bash`, runs under it whatever bash the PATH has first. Stub directories
     * put on PATH afterwards still come before it. Windows runs the launcher through Git Bash.
     */
    private fun ProcessBuilder.launcherBashFirst(): ProcessBuilder =
        if (onWindows) this else pathFirst(launcherBashDir)

    /** A process that runs the bundle's `play` with [args] under [launcherBash]. */
    private fun launcher(vararg args: String): ProcessBuilder = ProcessBuilder(play(*args)).launcherBashFirst()

    /** The command line that runs [script] through a POSIX shell. */
    private fun shell(script: String, vararg args: String): List<String> =
        if (onWindows) listOf(gitBash!!, "-c", script, *args) else listOf("/bin/sh", "-c", script, *args)

    /**
     * A path the launcher printed, in the form java.io.File understands: under
     * Git Bash it prints POSIX paths (/c/Users/..., or /tmp/... for the temp dir).
     */
    private fun hostPath(path: String): String =
        if (!onWindows) path
        else ProcessBuilder(shell("cygpath -w \"\$1\"", "bash", path))
            .start().inputStream.bufferedReader().readText().trim()

    /**
     * Windows only: wait for what `start` left running. The stub java exits at
     * once, but until it has its working directory and logs/system.out are in
     * use, and Windows will not let JUnit delete a @TempDir that is.
     */
    private fun settle(bundle: File) {
        if (!onWindows) return
        val wait = "for f in *.pid; do [ -f \"\$f\" ] || continue; n=0; " +
            "while kill -0 \"\$(cat \"\$f\")\" 2>/dev/null && [ \$n -lt 100 ]; do sleep 0.1; n=\$((n+1)); done; done"
        ProcessBuilder(shell(wait)).directory(bundle).redirectErrorStream(true).start()
            .apply { inputStream.readAllBytes() }.waitFor()
    }

    /** Materialize a minimal bundle (launcher + framework jar + .classpath) in [dir]. */
    private fun writeBundle(dir: File) {
        val template = javaClass.classLoader.getResource("bundle-play.sh")
            ?: error("bundle-play.sh not found on the test classpath")
        File(dir, "play").apply {
            writeText(template.readText().replace("__FW_VERSION__", fwVersion))
            setExecutable(true)
        }
        File(dir, "framework/lib").mkdirs()
        File(dir, "framework/play-$fwVersion.jar").writeText("")
        // Jars only, as playBundle writes it (PF-180).
        File(dir, ".classpath").writeText("framework/play-$fwVersion.jar\nframework/lib/netty.jar\n")
    }

    /**
     * Write a `java` stub that prints its argv one per line, plus — when
     * [windows] — `uname`/`cygpath` stubs that make the launcher take the MSYS
     * branch. The cygpath stub reproduces the shape of a real `cygpath -w`
     * result — drive letter plus backslashes — which is all we assert on.
     *
     * After its argv the stub prints an `ENV:name=value` line for each name in
     * `$STUB_PRINT_ENV` that is in its environment (PF-184), which is how the
     * certs/.env tests see what the JVM would have inherited.
     */
    private fun writeStubs(dir: File, windows: Boolean) {
        dir.mkdirs()
        fun stub(name: String, body: String) =
            File(dir, name).apply { writeText("#!/bin/bash\n$body\n"); setExecutable(true) }

        stub(
            "java",
            "printf '%s\\n' \"\$@\"\n" +
                "for v in \$STUB_PRINT_ENV; do [ -z \"\${!v+x}\" ] || printf 'ENV:%s=%s\\n' \"\$v\" \"\${!v}\"; done"
        )
        if (windows) {
            stub("uname", "echo MINGW64_NT-10.0-19045")
            // Real cygpath -w maps /x/y to a drive-lettered, backslashed path;
            // the drive it picks is irrelevant here, the shape is what we assert.
            stub("cygpath", "echo \"C:\$(echo \"\$2\" | tr '/' '\\\\')\"")
        }
    }

    /** Run `./play run [args]` in [bundle] with [stubs] prepended to PATH; return java's argv. */
    private fun launcherArgv(bundle: File, stubs: File, vararg args: String): List<String> {
        val proc = launcher("run", *args)
            .directory(bundle)
            .redirectErrorStream(true)
            .pathFirst(stubs)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "launcher should exit cleanly\n$out")
        return out.lines().filter { it.isNotEmpty() }
    }

    /**
     * Run `./play start [args]` in [bundle] against the stub `java` and return the
     * application-logging line, after checking the line before it names
     * logs/system.out as console output.
     */
    private fun startLogConfigLine(bundle: File, stubs: File, vararg args: String): String {
        // The stub java from a previous start may not have exited yet.
        File(bundle, "server.pid").delete()
        val proc = launcher("start", *args)
            .directory(bundle)
            .redirectErrorStream(true)
            .pathFirst(stubs)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "launcher should exit cleanly\n$out")
        settle(bundle)
        val lines = out.lines()
        val console = lines.indexOfFirst { it.startsWith("~ console output (stdout/stderr) -> ") }
        assertTrue(console >= 0, "start printed no console-output line:\n$out")
        assertEquals(
            File(bundle, "logs/system.out").canonicalPath,
            File(hostPath(lines[console].substringAfter(" -> "))).canonicalPath
        )
        return lines[console + 1]
    }

    /** One launcher run with stdout and stderr kept apart. */
    private class Launch(val exit: Int, val stdout: List<String>, val stderr: List<String>) {
        /** What the stub `java` had for [name]; null when it was not in its environment. */
        fun env(name: String): String? =
            stdout.firstOrNull { it.startsWith("ENV:$name=") }?.substringAfter('=')
    }

    /**
     * Run `./play [args]` in [bundle] with [env] added to the host environment
     * and the stub `java` reporting the [probe] variables. Unlike [launcherArgv]
     * this keeps stderr apart from stdout (PF-183: warnings belong on stderr) and
     * leaves the exit code to the caller. Lines are split on '\n' alone so a
     * carriage return that survived a CRLF certs/.env stays visible. [path] goes
     * on PATH after [stubs], for the test that wants a real `java` found there.
     */
    private fun launch(
        bundle: File,
        stubs: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
        probe: List<String> = emptyList(),
        path: List<File> = emptyList()
    ): Launch {
        val stderr = File(stubs, "stderr.txt")
        val proc = launcher(*args)
            .directory(bundle)
            .redirectError(stderr)
            .apply {
                pathFirst(stubs, *path.toTypedArray())
                // Only [env] is "the host" here: neither a probed name nor the two
                // variables the launcher reads itself may leak in from the
                // developer's shell. Nor UID/EUID: a shell that exports them (the
                // docker-compose idiom) makes them host-defined rather than readonly.
                environment().keys.removeAll(probe + listOf("PLAY_ID", "PLAY_PID_FILE", "PLAY_AOT_TIMEOUT", "PLAY_NODE", "PLAY_FRONTEND_WAIT", "PLAY_TEST_STOP_WAIT_SECONDS", "UID", "EUID"))
                environment()["STUB_PRINT_ENV"] = probe.joinToString(" ")
                environment().putAll(env)
            }
            .start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (args.firstOrNull() in setOf("start", "restart")) settle(bundle)
        fun lines(text: String) = text.split('\n').filter { it.isNotEmpty() }
        return Launch(exit, lines(stdout), lines(stderr.readText()))
    }

    /**
     * PF-227: whether a pattern range in [bash] follows the collation of [locale]: a bash before 5,
     * on a libc that collates by locale (macOS's does, musl's does not), with the locale installed.
     * Probed with T, which Estonian collation leaves out of A-Z.
     */
    private fun rangesFollowCollation(bash: String, locale: String): Boolean {
        val probe = ProcessBuilder(bash, "-c", "case T in [A-Z]) echo bytes;; *) echo collated;; esac")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["LC_ALL"] = locale }
            .start()
        return probe.inputStream.bufferedReader().readText().trim() == "collated"
    }

    /** A bundle under [tmp] whose certs/.env holds exactly [dotenv], and its stub directory. */
    private fun bundleWithDotEnv(tmp: File, dotenv: String): Pair<File, File> {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        File(bundle, "certs").mkdirs()
        File(bundle, "certs/.env").writeText(dotenv)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)
        return bundle to stubs
    }

    /** A bundle under [tmp] whose conf/application.conf holds exactly [conf], and its stub directory. */
    private fun bundleWithConf(tmp: File, conf: String): Pair<File, File> {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        File(bundle, "conf").mkdirs()
        File(bundle, "conf/application.conf").writeText(conf)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)
        return bundle to stubs
    }

    private fun classpathOf(argv: List<String>) = argv[argv.indexOf("-classpath") + 1]

    /** What the app asked for: java's argv between the launcher's own flags and -classpath. */
    private fun appJvmFlags(argv: List<String>) = argv.subList(
        argv.indexOf("-Dlog4j2.shutdownHookEnabled=false") + 1,
        argv.indexOf("-classpath")
    )

    private fun valueOf(argv: List<String>, prop: String) =
        argv.single { it.startsWith("-D$prop=") }.substringAfter('=')

    @Test
    fun `the launcher asks env for bash rather than naming a path to it`(@TempDir tmp: File) {
        // PF-226: NixOS keeps only /bin/sh and /usr/bin/env at fixed paths, and a
        // #!/bin/bash launcher died there as a bad interpreter before the JVM ran.
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)

        assertEquals("#!/usr/bin/env bash", File(bundle, "play").useLines { it.first() })
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "nothing on Windows reads a #! line: a bundle starts there through Git Bash")
    fun `the launcher runs under the first bash on PATH`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)
        // Ahead of the bash under test on PATH (PF-227), which it hands the launcher on to.
        File(stubs, "bash").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > bash.args\nexec '$launcherBash' \"\$@\"\n")
            setExecutable(true)
        }

        launcherArgv(bundle, stubs)

        assertEquals(listOf("./play", "run"), File(bundle, "bash.args").takeIf { it.isFile }?.readLines())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "asserts the branch the launcher takes off Windows")
    fun `launcher uses POSIX separator and untranslated paths off Windows`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)

        val argv = launcherArgv(bundle, stubs)

        assertEquals(
            "framework/play-$fwVersion.jar:framework/lib/netty.jar",
            classpathOf(argv),
            "off Windows the .classpath lines join with ':' — unchanged by PF-171"
        )
        assertEquals(bundle.canonicalPath, File(valueOf(argv, "application.path")).canonicalPath)
        assertEquals(File(bundle, "framework").canonicalPath, File(valueOf(argv, "framework.path")).canonicalPath)
    }

    @Test
    fun `launcher uses Windows separator and native paths under MSYS`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = true)

        val argv = launcherArgv(bundle, stubs)

        assertEquals(
            "framework/play-$fwVersion.jar;framework/lib/netty.jar",
            classpathOf(argv),
            "java.exe splits -classpath on ';' — a ':'-joined string is read as one nonexistent entry"
        )
        // Translated to a drive-lettered, backslash-separated path: a native JVM
        // reads a leading '/' as the current drive's root.
        listOf("application.path", "framework.path").forEach { prop ->
            val value = valueOf(argv, prop)
            assertTrue(
                Regex("""^[A-Za-z]:\\""").containsMatchIn(value),
                "-D$prop should be a native Windows path under MSYS but was '$value'"
            )
        }
        assertTrue(
            valueOf(argv, "framework.path").endsWith("""\framework"""),
            "framework.path must stay the bundle's framework/ subdir: ${valueOf(argv, "framework.path")}"
        )
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `under Git Bash on Windows the launcher hands java the bundle's own native paths`(@TempDir tmp: File) {
        // The test above fakes MSYS with a stubbed uname and cygpath, which is all
        // macOS and Linux can do. Here both are Git Bash's own, so the translation
        // is the real one and the result has to be the directory the bundle is in.
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)

        val argv = launcherArgv(bundle, stubs)

        assertEquals("framework/play-$fwVersion.jar;framework/lib/netty.jar", classpathOf(argv))
        assertEquals(bundle.canonicalPath, File(valueOf(argv, "application.path")).canonicalPath)
        assertEquals(File(bundle, "framework").canonicalPath, File(valueOf(argv, "framework.path")).canonicalPath)
    }

    @Test
    fun `the launcher starts no java agent of its own on either platform`(@TempDir tmp: File) {
        // PF-239: the framework jar's agent is HotSwap for DEV mode, and a bundle runs
        // precompiled, which is PROD; it cost every start java.instrument and a premain.
        listOf(false, true).forEach { windows ->
            val bundle = File(tmp, "app-$windows").apply { mkdirs() }
            writeBundle(bundle)
            val stubs = File(tmp, "bin-$windows")
            writeStubs(stubs, windows)

            val argv = launcherArgv(bundle, stubs)
            assertEquals(emptyList<String>(), argv.filter { it.startsWith("-javaagent:") }, "windows=$windows")
            assertTrue("-Dprecompiled=true" in argv, "a bundle runs precompiled, so in PROD mode:\n$argv")
        }
    }

    @Test
    fun `launcher bridges java util logging and lets the command line override it`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)
        fun julManagers(argv: List<String>) =
            argv.filter { it.startsWith("-Djava.util.logging.manager=") }.map { it.substringAfter('=') }

        // PF-175: without it, JUL output from bundled dependencies bypasses log4j2.
        assertEquals(
            listOf("org.apache.logging.log4j.jul.LogManager"),
            julManagers(launcherArgv(bundle, stubs))
        )
        // An operator's own manager must come after ours: the JVM honours the last -D.
        assertEquals(
            listOf("org.apache.logging.log4j.jul.LogManager", "com.example.AppLogManager"),
            julManagers(launcherArgv(bundle, stubs, "-Djava.util.logging.manager=com.example.AppLogManager"))
        )
    }

    @Test
    fun `launcher leaves stopping log4j to Play and lets conf and the command line turn its hook back on`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(tmp, "application.name=testapp\n")
        fun shutdownHooks(argv: List<String>) =
            argv.filter { it.startsWith("-Dlog4j2.shutdownHookEnabled=") }.map { it.substringAfter('=') }

        // PF-189: log4j's own hook stopped the appenders while onApplicationStop still logged.
        val argv = launcherArgv(bundle, stubs)
        assertEquals(listOf("false"), shutdownHooks(argv))
        assertEquals(argv.indexOf(julManager) + 1, argv.indexOf("-Dlog4j2.shutdownHookEnabled=false"), "beside the JUL manager: $argv")
        // Ours first, so the JVM keeps the application's: it honours the last -D.
        assertEquals(listOf("false", "true"), shutdownHooks(launcherArgv(bundle, stubs, "-Dlog4j2.shutdownHookEnabled=true")))
        File(bundle, "conf/application.conf").writeText("jvm.memory=-Dlog4j2.shutdownHookEnabled=true\n")
        assertEquals(listOf("false", "true"), shutdownHooks(launcherArgv(bundle, stubs)))
    }

    @Test
    fun `start names system out as console output and the log4j2 config for the play id`(@TempDir tmp: File) {
        // PF-176: same two lines as playStart/playRestart -- see OutputBannerTest.
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        File(bundle, "conf").mkdirs()
        File(bundle, "conf/application.conf").writeText(
            "application.log.path=/log4j2.xml\n%prod.application.log.path=/log4j2-prod.xml\n"
        )
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)

        // The bundle's play id defaults to prod, so its %prod. entry wins over the bare key.
        assertEquals("~ application logging follows /log4j2-prod.xml", startLogConfigLine(bundle, stubs))
        assertEquals("~ application logging follows /log4j2.xml", startLogConfigLine(bundle, stubs, "--%staging"))
    }

    @Test
    fun `start reports the default config when application log path is unset`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        File(bundle, "conf").mkdirs()
        File(bundle, "conf/application.conf").writeText("application.name=testapp\n")
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)

        assertEquals(
            "~ application logging follows the default log4j2 configuration (application.log.path is unset)",
            startLogConfigLine(bundle, stubs)
        )
    }

    @Test
    fun `module, preview and assertion switches reach the JVM in the order given`(@TempDir tmp: File) {
        // PF-183: the launcher used to forward six forms and drop the rest unannounced.
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)
        val forwarded = listOf(
            "--add-modules=jdk.incubator.vector",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-reads=app=ALL-UNNAMED",
            "--enable-native-access=app",
            "--enable-preview",
            "-ea", "-ea:com.example...", "-da", "-da:com.example.Noisy", "-esa", "-dsa",
            "-enableassertions", "-enableassertions:com.example...",
            "-disableassertions", "-disableassertions:com.example.Noisy",
            "-enablesystemassertions", "-disablesystemassertions"
        )

        val run = launch(bundle, stubs, "run", *forwarded.toTypedArray())

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        assertEquals(emptyList<String>(), run.stderr, "a forwarded option is not a dropped one")
        // Directly ahead of -classpath, where every other command-line JVM option goes.
        assertEquals(forwarded, run.stdout.subList(0, run.stdout.indexOf("-classpath")).takeLast(forwarded.size))
    }

    @Test
    fun `JVM flags in application conf reach the JVM ahead of the command line`(@TempDir tmp: File) {
        // PF-183: the Gradle launch paths lift these keys out of application.conf
        // (confJvmArgs in Play1Plugin.kt); a bundle ignored them, so an app whose conf
        // asks for --add-modules ran without it in production only.
        val (bundle, stubs) = bundleWithConf(
            tmp,
            "application.name=testapp\n" +
                "javaagent.path=/opt/agents/otel.jar\n" +
                "agentlib=jdwp=transport=dt_socket,server=y,address=8000\n" +
                "jvm.memory=-Xmx999m   --add-modules=jdk.incubator.vector\t-Dpattern=A*\n"
        )
        // Would be what -Dpattern=A* turns into if the flags were split by an unquoted expansion.
        File(bundle, "-Dpattern=A1").writeText("")

        assertEquals(
            listOf(
                "-javaagent:/opt/agents/otel.jar",
                "-agentlib:jdwp=transport=dt_socket,server=y,address=8000",
                "-Xmx999m", "--add-modules=jdk.incubator.vector", "-Dpattern=A*",
                // Last, so it is the -Xmx the JVM keeps.
                "-Xmx1g"
            ),
            appJvmFlags(launcherArgv(bundle, stubs, "-Xmx1g"))
        )
    }

    @Test
    fun `a conf JVM flag for the play id beats the bare key`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(tmp, "jvm.memory=-Xmx111m\n%prod.jvm.memory=-Xmx222m\n")

        assertEquals(listOf("-Xmx222m"), appJvmFlags(launcherArgv(bundle, stubs)))
        assertEquals(listOf("-Xmx111m"), appJvmFlags(launcherArgv(bundle, stubs, "--%staging")))
    }

    @Test
    fun `jmx port and hostname alone start an agent that demands a login and TLS`(@TempDir tmp: File) {
        // The 1.12 launcher hard-coded both protections off, so these two keys used to mean
        // an agent anyone who reached the port could drive. Now that is something to ask for.
        val (bundle, stubs) = bundleWithConf(tmp, "jmx.port=9010\njmx.hostname=127.0.0.1\n")
        assertEquals(
            listOf(
                "-Dcom.sun.management.jmxremote",
                "-Dcom.sun.management.jmxremote.port=9010",
                "-Dcom.sun.management.jmxremote.ssl=true",
                "-Dcom.sun.management.jmxremote.authenticate=true",
                "-Dcom.sun.management.jmxremote.local.only=false",
                "-Dcom.sun.management.jmxremote.host=127.0.0.1",
                "-Djava.rmi.server.hostname=127.0.0.1",
                // The registry is a listener of its own and has to follow jmx.ssl.
                "-Dcom.sun.management.jmxremote.registry.ssl=true"
            ),
            appJvmFlags(launcherArgv(bundle, stubs))
        )

        // One key alone opens nothing, and blank values are no flags at all.
        File(bundle, "conf/application.conf").writeText("jmx.port=9010\njvm.memory=   \njavaagent.path=\n")
        assertEquals(emptyList<String>(), appJvmFlags(launcherArgv(bundle, stubs)))
    }

    @Test
    fun `only the literal word false turns jmx authentication or TLS off`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(tmp, "")
        fun switches(conf: String): List<String> {
            File(bundle, "conf/application.conf").writeText("jmx.port=9010\njmx.hostname=10.0.0.5\n$conf")
            return appJvmFlags(launcherArgv(bundle, stubs))
                .filter { it.contains(".ssl=") || it.contains(".authenticate=") }
                .map { it.removePrefix("-Dcom.sun.management.jmxremote.") }
        }

        assertEquals(
            listOf("ssl=false", "authenticate=false", "registry.ssl=false"),
            switches("jmx.authenticate=false\njmx.ssl=FALSE\n")
        )
        assertEquals(
            listOf("ssl=true", "authenticate=false", "registry.ssl=true"),
            switches("jmx.authenticate=false\n")
        )
        // The JDK reads anything but "true" as off; passed through, a typo would open the agent.
        assertEquals(
            listOf("ssl=true", "authenticate=true", "registry.ssl=true"),
            switches("jmx.authenticate=ture\njmx.ssl=no\n")
        )
        // jvm.memory cannot switch it off either: the agent's flags follow it and the JVM
        // keeps the last -D, which is why these are keys of their own.
        assertEquals(
            listOf("authenticate=false", "ssl=true", "authenticate=true", "registry.ssl=true"),
            switches("jvm.memory=-Dcom.sun.management.jmxremote.authenticate=false\n")
        )
    }

    @Test
    fun `the files jmx authentication and TLS need are named in application conf`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(
            tmp,
            "jmx.port=9010\njmx.hostname=10.0.0.5\n" +
                "jmx.password.file=conf/jmx.password\njmx.access.file=conf/jmx.access\n" +
                "jmx.ssl.config.file=conf/jmx-ssl.properties\n"
        )
        // As files, not values: a credential or a keystore password given as a -D is readable in `ps`.
        assertEquals(
            listOf(
                "-Dcom.sun.management.jmxremote.ssl.config.file=conf/jmx-ssl.properties",
                "-Dcom.sun.management.jmxremote.password.file=conf/jmx.password",
                "-Dcom.sun.management.jmxremote.access.file=conf/jmx.access"
            ),
            appJvmFlags(launcherArgv(bundle, stubs)).takeLast(3)
        )
    }

    @Test
    fun `the launcher derives the same JVM flags from application conf as the Gradle launch paths`(@TempDir tmp: File) {
        // conf_jvm_args in bundle-play.sh is a bash port of confJvmArgs in Play1Plugin.kt.
        // One application.conf has to mean one JVM however the app is launched, so the
        // two are compared directly rather than each against its own expectations.
        val confs = listOf(
            "application.name=testapp\n",
            "jvm.memory=-Xms256m -Xmx2g\t-XX:+UseZGC   --add-modules=jdk.incubator.vector\n",
            "javaagent.path=bin/agent.jar\nagentlib=jdwp=transport=dt_socket,server=y,address=8000\n",
            "jvm.memory=-Xmx111m\n%prod.jvm.memory=-Xmx222m\n%test.javaagent.path=bin/jacocoagent.jar\n",
            // A blank value is unset; the Gradle-side reader used to take the next line for it.
            "jvm.memory=\njavaagent.path=bin/agent.jar\n",
            "javaagent.path=   \njvm.memory=-Xmx1g\nagentlib=\n",
            "jmx.port=9010\n",
            "jmx.port=\njmx.hostname=127.0.0.1\n",
            "jmx.port = 9010\njmx.hostname = 127.0.0.1\n",
            "jmx.port=9010\njmx.hostname=127.0.0.1\njmx.authenticate=false\njmx.ssl=False\n",
            "jmx.port=9010\njmx.hostname=127.0.0.1\njmx.authenticate=ture\njmx.ssl=\n",
            "jmx.port=9010\njmx.hostname=10.0.0.5\njmx.ssl=true\n%prod.jmx.ssl=false\n" +
                "jmx.password.file=conf/jmx.password\njmx.access.file=conf/jmx.access\n" +
                "jmx.ssl.config.file=conf/jmx-ssl.properties\njvm.memory=-Xmx1g\njavaagent.path=bin/agent.jar\n",
            // PF-229: --enable-preview for the classes Play compiled with it
            "java.enablePreview=true\n",
            "java.enablePreview = TRUE \njvm.memory=-Xmx1g\n",
            "java.enablePreview=yes\n",
            "java.enablePreview=true\n%staging.java.enablePreview=false\n",
            "%prod.java.enablePreview=true\njmx.port=9010\njmx.hostname=127.0.0.1\n"
        )
        confs.forEachIndexed { i, conf ->
            val (bundle, stubs) = bundleWithConf(File(tmp, "case$i").apply { mkdirs() }, conf)
            listOf("prod", "staging").forEach { id ->
                assertEquals(
                    confJvmArgs(bundle, id),
                    appJvmFlags(launcherArgv(bundle, stubs, "--%$id")),
                    "play id '$id', application.conf:\n$conf"
                )
            }
        }
    }

    @Test
    fun `an argument the launcher drops is named once on stderr and stdout stays clean`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)
        val baseline = launch(bundle, stubs, "run")
        // Only the =-joined long options are forwarded, so the two-word form is
        // two dropped arguments -- each reported, neither reaching the JVM.
        val dropped = listOf("--add-modules", "jdk.incubator.vector", "--stacktrace")

        val run = launch(bundle, stubs, "run", *dropped.toTypedArray())

        assertEquals(0, run.exit, "a dropped argument must not change the exit code\n${run.stderr}")
        assertEquals(baseline.stdout, run.stdout, "stdout must carry the JVM's output and nothing else")
        assertEquals(dropped.size, run.stderr.size, "one line per dropped argument:\n${run.stderr}")
        dropped.zip(run.stderr).forEach { (arg, line) ->
            assertTrue(line.contains("'$arg'"), "'$line' should name $arg")
        }

        // `status` and `pid` are read by scripts: same rule, and their own exit code survives.
        val status = launch(bundle, stubs, "status", "--stacktrace")
        assertEquals(1, status.exit)
        assertEquals(listOf("Not running"), status.stdout)
        assertEquals(1, status.stderr.size, "one line for the one dropped argument:\n${status.stderr}")
    }

    @Test
    fun `a warning that cannot be written does not stop the launcher`(@TempDir tmp: File) {
        // The launcher runs under `set -e`: with stderr closed the warning's echo
        // fails, and that must not end the launcher before it reaches the JVM.
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)

        val proc = ProcessBuilder(shell("./play run --stacktrace 2>&-"))
            .launcherBashFirst()
            .directory(bundle)
            .pathFirst(stubs)
            .start()
        val argv = proc.inputStream.bufferedReader().readText().lines()

        assertEquals(0, proc.waitFor(), "a dropped argument must not change the exit code")
        assertTrue("play.server.Server" in argv, "the JVM was never launched:\n$argv")
    }

    @Test
    fun `restart reports each warning once although it re-runs the launcher`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithDotEnv(tmp, "NOT A NAME=1\n")

        // restart is `"$0" stop` then `"$0" start "$@"`: certs/.env is read three
        // times and the argument parsed twice.
        val restart = launch(bundle, stubs, "restart", "--stacktrace")

        assertEquals(0, restart.exit, "launcher should exit cleanly\n${restart.stderr}")
        assertEquals(2, restart.stderr.size, "no warning may be printed twice:\n${restart.stderr}")
        assertTrue(restart.stderr[0].contains("line 1"), "'${restart.stderr[0]}' should be the certs/.env warning")
        assertTrue(restart.stderr[1].contains("'--stacktrace'"), "'${restart.stderr[1]}' should name the argument")
    }

    /**
     * PF-199: a process in place of the JVM `stop` stops, recorded in [bundle]'s pid
     * file. On SIGTERM it exits [termDelay] seconds later, or with null never does,
     * as a JVM still draining. It is this test's own child, so the JDK reaps it the
     * moment it exits, which is when `kill -0` stops finding it.
     */
    private fun standInJvm(bundle: File, termDelay: String?): Process {
        val onTerm = if (termDelay == null) "''" else "'kill \$!; sleep $termDelay; exit 0'"
        val jvm = ProcessBuilder("/bin/bash", "-c", "trap $onTerm TERM; echo ready; $STAND_IN_WAIT")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // The trap is set: a SIGTERM sent before it would end the shell at once.
        assertEquals("ready", jvm.inputStream.bufferedReader().readLine())
        File(bundle, "server.pid").writeText("${jvm.pid()}")
        return jvm
    }

    private fun Process.killTree() {
        descendants().forEach { it.destroyForcibly() }
        destroyForcibly().waitFor()
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the process in place of the JVM is a bash child that traps SIGTERM")
    // PF-219: a bound of 30 ms, so it runs in the build's serial task, never beside the parallel forks.
    @Tag("timing")
    fun `stop returns within 30 ms of the JVM's exit`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)

        // Exits at ten points spread over three poll intervals, so that some fall
        // just after a check: the old 100 ms poll had a median of about 60 ms here.
        val lags = (0 until 10).map { i ->
            val jvm = standInJvm(bundle, "0.%03d".format(7 * i))
            try {
                var exitedAt = 0L
                val watcher = Thread { jvm.waitFor(); exitedAt = System.nanoTime() }.apply { start() }
                val stop = launcher("stop").directory(bundle).redirectErrorStream(true).start()
                var stoppedAt = 0L
                val out = StringBuilder()
                stop.inputStream.bufferedReader().forEachLine { line ->
                    if (stoppedAt == 0L && "is stopped" in line) stoppedAt = System.nanoTime()
                    out.appendLine(line)
                }
                assertEquals(0, stop.waitFor(), "$out")
                watcher.join(5_000)
                assertTrue(stoppedAt != 0L && exitedAt != 0L, "stop did not see the exit:\n$out")
                assertFalse(File(bundle, "server.pid").exists(), "the pid file outlived the process:\n$out")
                (stoppedAt - exitedAt) / 1_000_000.0
            } finally {
                jvm.killTree()
            }
        }

        val sorted = lags.sorted()
        assertTrue(sorted[sorted.size / 2] < 30.0, "ms from the exit to \"is stopped\": $lags")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the process in place of the JVM is a bash child that traps SIGTERM")
    fun `stop leaves a JVM still draining after its wait running with its pid file`(@TempDir tmp: File) {
        // PF-219: the wait is 35 s; the test hook shortens it, and the stop wait's parity case
        // below pins the 35.
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin").apply { writeStubs(this, windows = false) }
        val jvm = standInJvm(bundle, termDelay = null)
        try {
            val started = System.nanoTime()
            val stop = launch(bundle, stubs, "stop", env = mapOf("PLAY_TEST_STOP_WAIT_SECONDS" to "2"))
            val seconds = (System.nanoTime() - started) / 1e9

            assertEquals(0, stop.exit, "${stop.stdout}\n${stop.stderr}")
            assertTrue(stop.stdout.any { "still draining after 2s" in it }, "${stop.stdout}")
            // The drain budget is elapsed time: at least the wait, and not that many seconds of polls.
            assertTrue(seconds >= 2.0 && seconds < 5.0, "stop waited $seconds s")
            assertTrue(jvm.isAlive, "stop must not kill a JVM that is still draining")
            assertEquals("${jvm.pid()}", File(bundle, "server.pid").readText(), "status and pid keep tracking it")
        } finally {
            jvm.killTree()
        }
    }

    @Test
    fun `the launcher's stop wait is the Gradle stop wait, 35 s unless the test hook says otherwise`(@TempDir tmp: File) {
        // PF-219: stop_wait_seconds in bundle-play.sh and stopWaitSeconds in Play1Plugin.kt are one
        // rule written twice. The launcher's function is run here as it is in the script, by bash,
        // over the same values as the Gradle side's own cases.
        val script = javaClass.classLoader.getResource("bundle-play.sh")!!.readText()
        val function = Regex("(?ms)^stop_wait_seconds\\(\\) \\{.*?^\\}").find(script)?.value
            ?: error("no stop_wait_seconds() in bundle-play.sh")
        // In a file, as the launcher has it. Passed as a `bash -c` argument, the function gave 35
        // for the value 2 under Git Bash on Windows CI (v1.13.104); a Windows command line built
        // from its quotes and globs is the likely cause, and a file avoids it either way.
        File(tmp, "stop-wait.sh").writeText("$function\nstop_wait_seconds\n")
        val bash = if (onWindows) gitBash!! else launcherBash
        fun launcherWait(value: String?): Long {
            val proc = ProcessBuilder(bash, "stop-wait.sh")
                .directory(tmp)
                .apply {
                    environment().remove("PLAY_TEST_STOP_WAIT_SECONDS")
                    if (value != null) environment()["PLAY_TEST_STOP_WAIT_SECONDS"] = value
                }
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            assertEquals(0, proc.waitFor(), out)
            return out.toLong()
        }
        assertEquals(35L, launcherWait(null))
        // Every ignored value gives 35 whether the hook reaches the function or not; this one does not.
        assertEquals(2L, launcherWait("2"), "PLAY_TEST_STOP_WAIT_SECONDS did not reach the launcher's function")
        for (value in STOP_WAIT_IGNORED + listOf("2", "08", "999999999")) {
            val gradleWait = stopWaitSeconds(mapOf("PLAY_TEST_STOP_WAIT_SECONDS" to value))
            assertEquals(gradleWait, launcherWait(value), "[$value]")
        }
    }

    @Test
    fun `host environment beats the dotenv file and the file supplies the rest`(@TempDir tmp: File) {
        // PF-184: the launcher sourced certs/.env with `set -a`, so the file won
        // over a variable the operator passed in, e.g. with `docker run -e`.
        val (bundle, stubs) = bundleWithDotEnv(
            tmp,
            "PF184_BOTH=from-file\nPF184_FILE_ONLY=from-file\nPF184_HOST_EMPTY=from-file\n"
        )

        val run = launch(
            bundle, stubs, "run",
            env = mapOf("PF184_BOTH" to "from-host", "PF184_HOST_EMPTY" to ""),
            probe = listOf("PF184_BOTH", "PF184_FILE_ONLY", "PF184_HOST_EMPTY")
        )

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        assertEquals("from-host", run.env("PF184_BOTH"))
        assertEquals("from-file", run.env("PF184_FILE_ONLY"))
        // Defined-but-empty is still defined: the host said "no value", not "ask the file".
        assertEquals("", run.env("PF184_HOST_EMPTY"))
    }

    @Test
    fun `dotenv values are literal text and never evaluated as shell`(@TempDir tmp: File) {
        // PF-184: sourcing turned `pa$$word#1` into the shell's pid and ran `$(...)`.
        val literal = "pa\$\$word#1 \$(touch PWNED) `touch PWNED` \${HOME} \$HOME a\\nb\\\\c ; & | * ~ \"mid\" 'mid'"
        val (bundle, stubs) = bundleWithDotEnv(tmp, "PF184_LITERAL=$literal\n")

        val run = launch(bundle, stubs, "run", probe = listOf("PF184_LITERAL"))

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        assertEquals(literal, run.env("PF184_LITERAL"))
        assertFalse(File(bundle, "PWNED").exists(), "a command substitution in certs/.env was executed")
    }

    @Test
    fun `dotenv splits at the first equals sign and strips one surrounding quote pair`(@TempDir tmp: File) {
        // Rule for rule what loadDotEnv in Play1Plugin.kt does: trim both sides,
        // then removeSurrounding("\"") followed by removeSurrounding("'").
        val (bundle, stubs) = bundleWithDotEnv(
            tmp,
            listOf(
                "# a comment",
                "",
                "  PF184_PADDED  =   padded value  ",
                "PF184_EQUALS=a=b=c",
                "PF184_EMPTY=",
                "PF184_HASH=value # not a comment",
                "PF184_DOUBLE=\"double quoted\"",
                "PF184_SINGLE='single quoted'",
                "PF184_INNER_SPACE=\"  kept  \"",
                "PF184_NESTED=\"'both pairs'\"",
                "PF184_REVERSED='\"inner pair stays\"'",
                "PF184_UNPAIRED=\"no closing quote",
                "PF184_LONE=\"",
                "=no key",
                "no equals sign"
            ).joinToString("\n", postfix = "\n")
        )
        val names = listOf(
            "PADDED", "EQUALS", "EMPTY", "HASH", "DOUBLE", "SINGLE", "INNER_SPACE", "NESTED", "REVERSED", "UNPAIRED", "LONE"
        )

        val run = launch(bundle, stubs, "run", probe = names.map { "PF184_$it" })

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        assertEquals(emptyList<String>(), run.stderr, "comments, blanks and lines without a key are skipped quietly")
        assertEquals(
            listOf(
                "padded value", "a=b=c", "", "value # not a comment", "double quoted", "single quoted", "  kept  ",
                "both pairs", "\"inner pair stays\"", "\"no closing quote", "\""
            ),
            names.map { run.env("PF184_$it") }
        )
    }

    @Test
    fun `dotenv tolerates CRLF line endings and a missing final newline`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithDotEnv(tmp, "PF184_FIRST=one\r\nPF184_QUOTED=\"two words\"\r\nPF184_LAST=three")

        val run = launch(bundle, stubs, "run", probe = listOf("PF184_FIRST", "PF184_QUOTED", "PF184_LAST"))

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        // A CR left on the value would also defeat the quote stripping.
        assertEquals("one", run.env("PF184_FIRST"))
        assertEquals("two words", run.env("PF184_QUOTED"))
        assertEquals("three", run.env("PF184_LAST"), "the last line counts without a trailing newline")
    }

    @Test
    fun `the last duplicate in the dotenv file wins`(@TempDir tmp: File) {
        // "Only if not already set" would make the first line win; loadDotEnv keeps the last.
        val (bundle, stubs) = bundleWithDotEnv(
            tmp,
            "PF184_DUP=first\nPF184_DUP=second\nPF184_DUP=third\nPF184_HOST_DUP=first\nPF184_HOST_DUP=second\n"
        )

        val run = launch(
            bundle, stubs, "run",
            env = mapOf("PF184_HOST_DUP" to "from-host"),
            probe = listOf("PF184_DUP", "PF184_HOST_DUP")
        )

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        assertEquals("third", run.env("PF184_DUP"))
        assertEquals("from-host", run.env("PF184_HOST_DUP"))
    }

    @Test
    fun `an invalid dotenv key is skipped with a warning and the launcher still starts`(@TempDir tmp: File) {
        val secret = "hunter2"
        val (bundle, stubs) = bundleWithDotEnv(
            tmp,
            "PF184_BEFORE=1\nMY-KEY=$secret\nexport PF184_EXPORTED=$secret\n1PF184=$secret\nPF184_AFTER=2\n"
        )

        val run = launch(bundle, stubs, "run", probe = listOf("PF184_BEFORE", "PF184_AFTER", "PF184_EXPORTED"))

        assertEquals(0, run.exit, "a bad line must not stop the launcher\n${run.stderr}")
        assertEquals(listOf("1", "2"), listOf(run.env("PF184_BEFORE"), run.env("PF184_AFTER")))
        // No `export` prefix in this grammar: the key is "export PF184_EXPORTED", which is not a name.
        assertNull(run.env("PF184_EXPORTED"))
        assertEquals(3, run.stderr.size, "one line per skipped key:\n${run.stderr}")
        listOf(2, 3, 4).zip(run.stderr).forEach { (number, line) ->
            assertTrue(Regex("""\bline $number\b""").containsMatchIn(line), "'$line' should give line $number")
            // The line number is all it may say: the rest of the line can be a secret.
            assertTrue(listOf(secret, "MY-KEY", "EXPORTED", "1PF184").none { it in line }, "'$line' echoes the file")
        }
    }

    @Test
    fun `a dotenv key is a name under any locale collation`(@TempDir tmp: File) {
        // Before bash 5 a pattern range such as A-Z follows the locale, and Estonian
        // collation leaves T to Y out of it -- PLAY_SECRET stopped being a name. Has
        // teeth only where that bash and that locale both exist (macOS /bin/bash 3.2).
        // PF-227: elsewhere it would pass without testing anything, so it says so instead.
        val bash = if (onWindows) gitBash!! else launcherBash
        assumeTrue(
            rangesFollowCollation(bash, "et_EE.UTF-8"),
            "$bash leaves a pattern range to byte order under et_EE.UTF-8, so it cannot show the bug"
        )
        val (bundle, stubs) = bundleWithDotEnv(tmp, "PF184_TUVWXY_tuvwxy=kept\n")

        val run = launch(
            bundle, stubs, "run",
            env = mapOf("LC_ALL" to "et_EE.UTF-8"),
            probe = listOf("PF184_TUVWXY_tuvwxy")
        )

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        assertEquals("kept", run.env("PF184_TUVWXY_tuvwxy"), run.stderr.joinToString("\n"))
    }

    @Test
    fun `names bash or the launcher already uses cannot break the launcher`(@TempDir tmp: File) {
        // UID and EUID are readonly in bash; IFS and PATH steer the launcher's own
        // word splitting and command lookup; JAVA_CMD, CP and SCRIPT_DIR are variables
        // of the script itself. None of them may abort it or change what it runs.
        val (bundle, stubs) = bundleWithDotEnv(
            tmp,
            "IFS=1\nPATH=/nonexistent\nUID=0\nEUID=0\n" +
                "JAVA_CMD=not-java\nCP=not-a-classpath\nSCRIPT_DIR=/elsewhere\nPF184_AFTER=still loaded\n"
        )
        val names = listOf("JAVA_CMD", "CP", "SCRIPT_DIR", "PF184_AFTER")

        val run = launch(bundle, stubs, "run", probe = names)

        assertEquals(0, run.exit, "launcher should exit cleanly\n${run.stderr}")
        assertEquals(
            listOf("framework/play-$fwVersion.jar", "framework/lib/netty.jar").joinToString(cpSep),
            classpathOf(run.stdout)
        )
        assertEquals(bundle.canonicalPath, File(valueOf(run.stdout, "application.path")).canonicalPath)
        // They are the application's environment all the same...
        assertEquals(listOf("not-java", "not-a-classpath", "/elsewhere", "still loaded"), names.map { run.env(it) })
        // ...bar the two bash lets nobody set, which are reported like any other skipped line.
        assertEquals(2, run.stderr.size, "one line each for UID and EUID:\n${run.stderr}")
        listOf(3, 4).zip(run.stderr).forEach { (number, line) ->
            assertTrue(Regex("""\bline $number\b""").containsMatchIn(line), "'$line' should give line $number")
        }
    }

    @Test
    fun `play id and pid file in the dotenv file are defaults the host and the flags override`(@TempDir tmp: File) {
        // The launcher reads PLAY_ID and PLAY_PID_FILE itself, so certs/.env has to
        // be loaded before those defaults are taken -- and must not then beat --%<id>.
        val (bundle, stubs) = bundleWithDotEnv(tmp, "PLAY_ID=fromfile\nPLAY_PID_FILE=from-file.pid\n")
        fun playId(vararg args: String, env: Map<String, String> = emptyMap()) =
            valueOf(launch(bundle, stubs, "run", *args, env = env).stdout, "play.id")

        assertEquals("fromfile", playId())
        assertEquals("staging", playId("--%staging"))
        assertEquals("fromhost", playId(env = mapOf("PLAY_ID" to "fromhost")))

        launch(bundle, stubs, "start")
        assertTrue(File(bundle, "from-file.pid").isFile, "PLAY_PID_FILE from certs/.env should name the pid file")
        launch(bundle, stubs, "start", "--pid-file=from-flag.pid")
        assertTrue(File(bundle, "from-flag.pid").isFile, "--pid-file should beat PLAY_PID_FILE from certs/.env")
        launch(bundle, stubs, "start", env = mapOf("PLAY_PID_FILE" to "from-host.pid"))
        assertTrue(File(bundle, "from-host.pid").isFile, "the host's PLAY_PID_FILE should beat certs/.env")
    }

    @Test
    fun `launcher starts a real JVM that finds the bundle, the conf flags and the dotenv entries`(@TempDir tmp: File) {
        // Every other test hands java's argv to a stub. This one lets the launcher
        // start the JVM the tests run on -- on Windows a native java.exe behind
        // Git Bash, the boundary PF-171 is about -- and asks that JVM what arrived.
        // A wrong separator or an untranslated path stops an installed bundle
        // booting, and no assertion on argv can show it.
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        reportingFrameworkJar(File(tmp, "fake"), File(bundle, "framework/play-$fwVersion.jar"))
        File(bundle, ".classpath").writeText("framework/play-$fwVersion.jar\nlib/marker.jar\n")
        File(bundle, "conf").mkdirs()
        File(bundle, "conf/application.conf")
            .writeText("jvm.memory=--add-modules=jdk.incubator.vector -Dpf183.from.conf=yes\n")
        File(bundle, "lib").mkdirs()
        JarOutputStream(File(bundle, "lib/marker.jar").outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("marker.txt"))
            jar.write("only reachable through -classpath".toByteArray())
            jar.closeEntry()
        }
        File(bundle, "certs").mkdirs()
        File(bundle, "certs/.env").writeText("PF184_FILE=pa\$\$word#1 from file\nPF184_BOTH=from-file\n")
        val noStubs = File(tmp, "bin").apply { mkdirs() }

        val run = launch(
            bundle, noStubs, "run", "-Dpf183.from.cli=yes", "--http.port=19183",
            env = mapOf("PF184_BOTH" to "from-host"),
            path = listOf(File(System.getProperty("java.home"), "bin"))
        )

        assertEquals(0, run.exit, "the JVM should start and exit cleanly\n${run.stdout}\n${run.stderr}")
        // A native JVM ends its lines with CRLF on Windows, and launch() splits on LF alone.
        fun reported(name: String) =
            run.stdout.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trimEnd('\r')
        assertEquals(bundle.canonicalPath, reported("APP"), "application.path is not the bundle")
        assertEquals(File(bundle, "framework").canonicalPath, reported("FRAMEWORK"), "framework.path")
        // A file only the second jar holds is what shows the classpath took, separator
        // included: until PF-239 the launcher's -javaagent kept the framework jar
        // loadable even when -classpath was misread. (It was a file in conf/ until
        // PF-180 took directories off the bundle's classpath.)
        assertEquals("true", reported("SECOND_JAR_ON_CLASSPATH"), "-classpath did not take\n${run.stdout}")
        assertEquals("true", reported("VECTOR"), "jvm.memory's --add-modules did not reach the JVM")
        assertEquals("yes", reported("FROM_CONF"))
        assertEquals("yes", reported("FROM_CLI"))
        assertEquals("pa\$\$word#1 from file", reported("ENV_FILE"))
        assertEquals("from-host", reported("ENV_BOTH"))
        assertEquals("--http.port=19183", reported("ARGS"))
    }

    // ---- PF-180: the JDK's AOT cache -------------------------------------------------
    //
    // `aot-gen` writes app.aot in the bundle root from a training run; `run` and
    // `start` hand it to the JVM when it is there. Under Git Bash the launcher
    // refuses to train (kill is a hard kill there, and the JVM writes the cache at
    // a normal exit), so every test that needs a training run is off on Windows.

    private val julManager = "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager"

    private fun aotFlags(argv: List<String>) = argv.filter { it.startsWith("-XX:AOT") }

    /** A port nothing listens on: handed out by the OS and released again. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Run `./play start [args]` against the stub `java` and return the argv it left
     * in logs/system.out. `start` returns once the JVM is spawned, so the stub's
     * output is waited for.
     */
    private fun startArgv(bundle: File, stubs: File, vararg args: String): List<String> {
        File(bundle, "server.pid").delete()
        val systemOut = File(bundle, "logs/system.out").apply { delete() }
        val start = launch(bundle, stubs, "start", *args)
        assertEquals(0, start.exit, "launcher should exit cleanly\n${start.stderr}")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (systemOut.isFile && "play.server.Server" in systemOut.readLines()) break
            Thread.sleep(50)
        }
        return systemOut.readLines().filter { it.isNotEmpty() }
    }

    /** One `./play aot-gen` run, and what the JVM it started wrote (logs/aot-gen.out). */
    private class Training(val launch: Launch, val jvmOutput: List<String>)

    private fun aotGen(
        bundle: File,
        stubs: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
        path: List<File> = emptyList()
    ): Training {
        val jvmOut = File(bundle, "logs/aot-gen.out").apply { delete() }
        val run = launch(bundle, stubs, "aot-gen", *args, env = env, path = path)
        return Training(run, if (jvmOut.isFile) jvmOut.readLines().filter { it.isNotEmpty() } else emptyList())
    }

    /** What must be true after any `aot-gen` that failed: nothing new, nothing touched. */
    private fun assertLeftAsItWas(bundle: File) {
        assertEquals("4242", File(bundle, "server.pid").readText(), "aot-gen touched a running instance's pid file")
        assertEquals("the cache in use", File(bundle, "app.aot").readText(), "aot-gen damaged the cache in use")
        assertEquals(
            emptyList<String>(),
            bundle.list()!!.filter { it.startsWith("app.aot.") },
            "a half-written cache was left behind"
        )
    }

    /** A bundle that already has a cache and the pid file of an instance `start` is tracking. */
    private fun bundleInUse(tmp: File, conf: String = "application.name=testapp\n"): Pair<File, File> {
        val (bundle, stubs) = bundleWithConf(tmp, conf)
        File(bundle, "server.pid").writeText("4242")
        File(bundle, "app.aot").writeText("the cache in use")
        return bundle to stubs
    }

    @Test
    fun `run and start use the AOT cache only when the bundle has one`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(tmp, "jvm.memory=-Xmx111m\n")

        assertEquals(emptyList<String>(), aotFlags(launcherArgv(bundle, stubs)))
        assertEquals(emptyList<String>(), aotFlags(startArgv(bundle, stubs)))

        File(bundle, "app.aot").writeText("a cache, for all the launcher can tell")
        val run = launcherArgv(bundle, stubs, "-Xmx1g")
        // Relative like -javaagent, so a native Windows JVM resolves it too.
        assertEquals(listOf("-XX:AOTCache=app.aot"), aotFlags(run))
        assertEquals(listOf("-XX:AOTCache=app.aot"), aotFlags(startArgv(bundle, stubs)))
        // Ahead of everything application.conf and the command line supply: the JVM
        // keeps the last value it is given for an option.
        assertTrue(run.indexOf("-XX:AOTCache=app.aot") < run.indexOf(julManager), "$run")
        assertEquals(listOf("-Xmx111m", "-Xmx1g"), appJvmFlags(run), "the cache flag is not one of the app's own")
    }

    @Test
    fun `an AOT or CDS option of the operator's own wins over the cache the launcher would use`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(tmp, "application.name=testapp\n")
        File(bundle, "app.aot").writeText("a cache, for all the launcher can tell")
        fun argv(vararg args: String) = launcherArgv(bundle, stubs, *args)

        // The JVM takes these next to -XX:AOTCache, and the later one decides.
        assertEquals(
            listOf("-XX:AOTCache=app.aot", "-XX:AOTCache=/srv/caches/other.aot"),
            aotFlags(argv("-XX:AOTCache=/srv/caches/other.aot"))
        )
        assertEquals(listOf("-XX:AOTCache=app.aot", "-XX:AOTMode=off"), aotFlags(argv("-XX:AOTMode=off")))
        assertEquals(listOf("-XX:AOTCache=app.aot", "-XX:AOTMode=on"), aotFlags(argv("-XX:AOTMode=on")))

        // With these it refuses to start as long as -XX:AOTCache is there as well,
        // so for the operator's option to win the launcher's has to go.
        listOf(
            listOf("-Xshare:off"),
            listOf("-Xshare:auto"),
            listOf("-XX:SharedArchiveFile=app.jsa"),
            listOf("-XX:SharedClassListFile=app.classlist"),
            listOf("-XX:DumpLoadedClassList=app.classlist"),
            listOf("-XX:AOTCacheOutput=new.aot"),
            listOf("-XX:AOTMode=record", "-XX:AOTConfiguration=app.aotconf"),
            listOf("-XX:AOTMode=create", "-XX:AOTConfiguration=app.aotconf")
        ).forEach { own ->
            val run = argv(*own.toTypedArray())
            assertFalse("-XX:AOTCache=app.aot" in run, "$own cannot be combined with -XX:AOTCache:\n$run")
            assertEquals(own, appJvmFlags(run))
        }

        // application.conf speaks for the operator as well.
        File(bundle, "conf/application.conf").writeText("jvm.memory=-Xmx1g -Xshare:off\n")
        assertEquals(emptyList<String>(), aotFlags(argv()))
        // An unrelated option changes nothing.
        File(bundle, "conf/application.conf").writeText("jvm.memory=-Xmx1g -XX:+UseZGC\n")
        assertEquals(listOf("-XX:AOTCache=app.aot"), aotFlags(argv("-XX:MaxGCPauseMillis=50")))
    }

    @Test
    fun `the launcher leaves its cache option out for the same options as the Gradle launch paths`(@TempDir tmp: File) {
        // aot_cache_arg in bundle-play.sh and aotCacheArg in Play1Plugin.kt (PF-185) are one
        // rule written twice: which options of the operator's own the JVM refuses to start
        // with next to -XX:AOTCache. An option missing from one of the two lists is a start
        // that fails only for the applications launched that way, so the two are compared
        // directly rather than each against its own expectations.
        val (bundle, stubs) = bundleWithConf(tmp, "application.name=testapp\n")
        val cases = listOf(
            "" to emptyList(),
            "" to listOf("-Xmx1g", "-XX:+UseZGC", "-Dpf185=-Xshare:off"),
            "" to listOf("-XX:AOTCache=/srv/caches/other.aot"),
            "" to listOf("-XX:AOTMode=off"),
            "" to listOf("-XX:AOTMode=on"),
            "" to listOf("-XX:AOTMode=auto"),
            "" to listOf("-XX:AOTMode=record", "-XX:AOTConfiguration=app.aotconf"),
            "" to listOf("-XX:AOTMode=create"),
            "" to listOf("-XX:AOTConfiguration=app.aotconf"),
            "" to listOf("-XX:AOTCacheOutput=new.aot"),
            "" to listOf("-Xshare:off"),
            "" to listOf("-Xshare:auto"),
            "" to listOf("-Xshare:on"),
            "" to listOf("-Xshare:dump"),
            "" to listOf("-XX:SharedArchiveFile=app.jsa"),
            "" to listOf("-XX:SharedClassListFile=app.classlist"),
            "" to listOf("-XX:DumpLoadedClassList=app.classlist"),
            "" to listOf("-XX:+AOTClassLinking"),
            // application.conf speaks for the operator as well.
            "jvm.memory=-Xmx1g -Xshare:off\n" to emptyList(),
            "jvm.memory=-Xmx1g -XX:+UseZGC\n" to listOf("-XX:MaxGCPauseMillis=50"),
            "jvm.memory=-XX:AOTMode=record\n%prod.jvm.memory=-Xmx1g\n" to emptyList(),
            "jvm.memory=-Xmx1g\n%prod.jvm.memory=-XX:AOTMode=record\n" to emptyList()
        )
        // Without a cache neither has an option to give.
        assertNull(aotCacheArg(bundle, emptyList()))
        assertEquals(emptyList<String>(), aotFlags(launcherArgv(bundle, stubs)))

        // Each has its cache where it keeps it: the launcher in the bundle's root, the
        // plugin under the application's build directory.
        File(bundle, "app.aot").writeText("a cache, for all either can tell")
        File(bundle, "build/play/aot").mkdirs()
        File(bundle, "build/play/aot/app.aot").writeText("a cache, for all either can tell")
        cases.forEach { (conf, commandLine) ->
            File(bundle, "conf/application.conf").writeText("application.name=testapp\n$conf")
            assertEquals(
                // PLAY_ID is prod when the launcher is given none.
                aotCacheArg(bundle, confJvmArgs(bundle, "prod") + commandLine) != null,
                "-XX:AOTCache=app.aot" in launcherArgv(bundle, stubs, *commandLine.toTypedArray()),
                "application.conf: [${conf.trim()}], command line: $commandLine"
            )
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen starts the JVM with the options of run plus the cache output flag`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(
            tmp,
            "jvm.memory=-Xmx111m -XX:+UseZGC\n%staging.jvm.memory=-Xmx222m -XX:+UseZGC\njavaagent.path=bin/agent.jar\n"
        )
        val args = arrayOf(
            "--%staging", "-Xmx1g", "--add-modules=jdk.incubator.vector", "-Dpf180=yes", "--http.port=${freePort()}"
        )
        val run = launcherArgv(bundle, stubs, *args)

        // The stub java exits at once, which aot-gen reports as a failed start; its
        // argv is the subject here. A cache trained under other options -- another
        // collector, a missing --add-modules -- is one the JVM rejects in production.
        val slot = run.indexOf(julManager)
        assertEquals(
            run.take(slot) + "-XX:AOTCacheOutput=app.aot.new" + run.drop(slot),
            aotGen(bundle, stubs, *args).jvmOutput
        )

        // The cache being replaced is not an input to its own replacement, and the
        // JVM refuses -XX:AOTCache next to -XX:AOTCacheOutput.
        File(bundle, "app.aot").writeText("the cache in use")
        assertEquals(listOf("-XX:AOTCacheOutput=app.aot.new"), aotFlags(aotGen(bundle, stubs, *args).jvmOutput))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen takes the port from the command line, then from application conf`(@TempDir tmp: File) {
        val (bare, staging, given) = Triple(freePort(), freePort(), freePort())
        val (bundle, stubs) = bundleWithConf(tmp, "http.port=$bare\n%staging.http.port=$staging\n")
        fun ports(vararg args: String) =
            aotGen(bundle, stubs, *args).jvmOutput.filter { it.startsWith("--http.port=") }.map { it.substringAfter('=') }

        // The port it waits on is the port it tells the application to use, so the
        // two cannot differ however application.conf is put together.
        assertEquals(listOf("$bare"), ports())
        assertEquals(listOf("$staging"), ports("--%staging"))
        assertEquals(listOf("$given"), ports("--http.port=$given"))

        // A value the launcher cannot read as a port is not guessed at.
        File(bundle, "conf/application.conf").writeText("http.port=\${HTTP_PORT}\n")
        val unreadable = aotGen(bundle, stubs)
        assertEquals(1, unreadable.launch.exit)
        assertTrue(unreadable.launch.stderr.any { "--http.port=" in it }, "should say how to give the port:\n${unreadable.launch.stderr}")
        assertEquals(emptyList<String>(), unreadable.jvmOutput, "the JVM must not have been started")
        // ...and with the port given, that conf is no obstacle.
        assertEquals(listOf("$given"), ports("--http.port=$given"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen refuses a port that is in use and starts nothing`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleInUse(tmp)

        val training = ServerSocket(0).use { taken -> aotGen(bundle, stubs, "--http.port=${taken.localPort}") }

        assertEquals(1, training.launch.exit)
        assertTrue(training.launch.stderr.any { "already in use" in it }, "${training.launch.stderr}")
        assertEquals(emptyList<String>(), training.jvmOutput, "the JVM must not have been started")
        assertLeftAsItWas(bundle)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen reports an application that fails to start and leaves no partial cache`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleInUse(tmp)
        // A JVM that got as far as opening its output files before it died.
        File(stubs, "java").writeText(
            "#!/bin/bash\n" +
                "for a in \"\$@\"; do case \"\$a\" in -XX:AOTCacheOutput=*) " +
                "echo partial > \"\${a#*=}\"; echo partial > \"\${a#*=}.config\" ;; esac; done\n" +
                "echo 'Could not bind on port'\nexit 1\n"
        )

        val training = aotGen(bundle, stubs, "--http.port=${freePort()}")

        assertEquals(1, training.launch.exit)
        assertTrue(training.launch.stderr.any { "stopped before" in it }, "${training.launch.stderr}")
        // Why it stopped is in the application's own output: the tail of it is shown.
        assertTrue(training.launch.stderr.any { "Could not bind on port" in it }, "${training.launch.stderr}")
        assertLeftAsItWas(bundle)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen gives up on an application that never listens and stops it`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleInUse(tmp)
        // Up, but never listening. Its pid shows whether aot-gen stopped it.
        File(stubs, "java").writeText("#!/bin/bash\necho \$\$ > training.pid\nexec sleep 60\n")

        // Two seconds, not one: the launcher counts in whole seconds, so "1" can
        // be over before the stub has written its pid.
        val training = aotGen(bundle, stubs, "--http.port=${freePort()}", env = mapOf("PLAY_AOT_TIMEOUT" to "2"))

        assertEquals(1, training.launch.exit)
        assertTrue(training.launch.stderr.any { "after 2s" in it }, "${training.launch.stderr}")
        val pid = File(bundle, "training.pid").readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the training run (pid $pid) was left running")
        assertLeftAsItWas(bundle)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen does not take a cache the JVM did not finish assembling`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleInUse(tmp)
        listeningFrameworkJar(File(tmp, "fake"), File(bundle, "framework/play-$fwVersion.jar"))
        File(bundle, ".classpath").writeText("framework/play-$fwVersion.jar\n")
        // The JVM assembles the cache in a child process of its own. When that
        // child dies part way -- out of memory in a container, say -- the JVM still
        // exits as it does for any SIGTERM, but leaves what was written of the
        // cache and, beside it, the recording it removes once a cache is complete
        // (seen on 25.0.2 by killing the child). This `java` runs the application
        // for real, without the cache option, and leaves exactly that behind.
        File(stubs, "java").writeText(
            "#!/bin/bash\n" +
                "args=()\n" +
                "for a in \"\$@\"; do case \"\$a\" in -XX:AOTCacheOutput=*) out=\"\${a#*=}\" ;; *) args+=(\"\$a\") ;; esac; done\n" +
                "\"\$REAL_JAVA\" \"\${args[@]}\" &\n" +
                "app=\$!\n" +
                "trap 'kill \$app; wait \$app; echo partial > \"\$out\"; echo recording > \"\$out.config\"; " +
                "echo \"[9.9s][error  ][aot] Child process failed; status = 137\"; exit 143' TERM\n" +
                "wait \$app\n"
        )

        val training = aotGen(
            bundle, stubs, "--http.port=${freePort()}",
            env = mapOf("REAL_JAVA" to File(System.getProperty("java.home"), "bin/java").absolutePath)
        )

        assertTrue("REQUEST GET / HTTP/1.1" in training.jvmOutput, "the run never got as far as stopping:\n${training.jvmOutput}")
        assertEquals(1, training.launch.exit, "${training.launch.stdout}\n${training.launch.stderr}")
        assertTrue(training.launch.stderr.any { "without completing the cache" in it }, "${training.launch.stderr}")
        // The JVM's own word on why, which is an [aot] line like its warnings.
        assertTrue(training.launch.stderr.any { "Child process failed" in it }, "${training.launch.stderr}")
        assertLeftAsItWas(bundle)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen reads PLAY_AOT_TIMEOUT as a decimal number`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleInUse(tmp)

        // bash arithmetic takes a leading zero for octal: 08 is an error there, and
        // it used to surface after the JVM had been started. The stub java exits at
        // once, so a timeout that is understood ends in the ordinary failed start.
        val training = aotGen(bundle, stubs, "--http.port=${freePort()}", env = mapOf("PLAY_AOT_TIMEOUT" to "08"))

        assertEquals(1, training.launch.exit)
        assertTrue(training.launch.stderr.any { "stopped before" in it }, "${training.launch.stderr}")
        assertFalse(training.launch.stderr.any { "value too great" in it }, "${training.launch.stderr}")
        assertLeftAsItWas(bundle)
    }

    @Test
    fun `aot-gen refuses under MSYS and run still uses a cache that is there`(@TempDir tmp: File) {
        val (bundle, _) = bundleInUse(tmp)
        val stubs = File(tmp, "msys-bin")
        writeStubs(stubs, windows = true)

        val training = aotGen(bundle, stubs, "--http.port=${freePort()}")

        assertEquals(1, training.launch.exit)
        assertTrue(training.launch.stderr.any { "aot-gen" in it && "Git Bash" in it }, "${training.launch.stderr}")
        assertEquals(emptyList<String>(), training.jvmOutput, "the JVM must not have been started")
        assertLeftAsItWas(bundle)
        // A relative path, which needs no translation for a native JVM.
        assertEquals(listOf("-XX:AOTCache=app.aot"), aotFlags(launcherArgv(bundle, stubs)))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "aot-gen refuses under Git Bash, where kill cannot stop a JVM gracefully")
    fun `aot-gen trains a real JVM and run then starts from the cache it wrote`(@TempDir tmp: File) {
        assumeTrue(Runtime.version().feature() >= 25, "-XX:AOTCacheOutput needs JDK 25")
        val (bundle, _) = bundleInUse(tmp, conf = "jvm.memory=-Xmx256m\n")
        listeningFrameworkJar(File(tmp, "fake"), File(bundle, "framework/play-$fwVersion.jar"))
        File(bundle, ".classpath").writeText("framework/play-$fwVersion.jar\n")
        val noStubs = File(tmp, "no-stubs").apply { mkdirs() }
        val javaBin = listOf(File(System.getProperty("java.home"), "bin"))

        val training = aotGen(bundle, noStubs, "--http.port=${freePort()}", path = javaBin)

        assertEquals(0, training.launch.exit, "${training.launch.stdout}\n${training.launch.stderr}\n${training.jvmOutput}")
        assertTrue("REQUEST GET / HTTP/1.1" in training.jvmOutput, "no request was served:\n${training.jvmOutput}")
        // The JVM writes the cache on its way out of a normal exit, hooks included.
        assertTrue("SHUTDOWN HOOK" in training.jvmOutput, "the JVM was not stopped gracefully:\n${training.jvmOutput}")
        val cache = File(bundle, "app.aot")
        assertTrue(cache.length() > 1_000_000, "app.aot is ${cache.length()} bytes: not a cache")
        assertEquals(emptyList<String>(), bundle.list()!!.filter { it.startsWith("app.aot.") }, "leftovers of the training run")
        assertEquals("4242", File(bundle, "server.pid").readText(), "aot-gen touched a running instance's pid file")
        // Where it is and how big.
        val report = training.launch.stdout.single { "app.aot" in it }
        assertEquals(cache.canonicalPath, File(report.substringAfter(": ").substringBefore(" (")).canonicalPath, report)
        assertTrue(Regex("""\(\d+ MB\)$""").containsMatchIn(report), report)

        // -XX:AOTMode=on makes the JVM refuse to start unless it can use the cache
        // it is given, so a clean exit here is the JVM's own word that the cache
        // aot-gen wrote fits the options run starts it with.
        val run = launch(bundle, noStubs, "run", "-XX:AOTMode=on", "-Dpf180.exit=true", path = javaBin)
        assertEquals(0, run.exit, "the JVM rejected the cache:\n${run.stdout}\n${run.stderr}")
        assertTrue(run.stdout.any { it.trimEnd('\r') == "STARTED WITH -XX:AOTCache=app.aot" }, "${run.stdout}")
    }

    // ---- PF-212: Nuxt's server beside Play --------------------------------------------
    //
    // A bundle whose frontend sets ssr: true carries frontend/.output/server/index.mjs,
    // and the launcher runs `node frontend/.output/play1-server.mjs` beside Play. Play is
    // the listening framework jar on the JVM the tests run on; node is a stub that logs
    // to node-stub.log (relative, so Git Bash needs no path translation) what it got,
    // whether Play was listening when it started and when it was stopped, and exits only
    // when its stop file appears: the one way the launcher stops it on every OS.

    private val nodeRange = "^20.19.0 || >=22.12.0"

    private val javaBin get() = listOf(File(System.getProperty("java.home"), "bin"))

    private fun writeStubNode(stubs: File) {
        File(stubs, "node").apply {
            writeText(
                "#!/bin/bash\n" +
                    "log=node-stub.log\n" +
                    "if [ \"\$2\" = --check ]; then\n" +
                    "    echo \"check \$1\" >> \$log\n" +
                    "    [ -z \"\$STUB_NODE_TOO_OLD\" ] || { echo \"play: Nuxt's server needs Node ^99.0.0; this is Node 1.0.0\" >&2; exit 1; }\n" +
                    "    exit 0\n" +
                    "fi\n" +
                    "play=\"\${NUXT_PLAY_BACKEND_URL#http://}\"\n" +
                    "listening() { (exec 3<>\"/dev/tcp/\${play%:*}/\${play##*:}\") 2>/dev/null; }\n" +
                    "if listening; then echo play-listening-at-start >> \$log; else echo play-not-listening-at-start >> \$log; fi\n" +
                    "for v in NITRO_PORT NITRO_HOST NUXT_PLAY_BACKEND_URL PLAY1_STOP_FILE PF212_FROM_DOTENV; do echo \"ENV:\$v=\${!v}\" >> \$log; done\n" +
                    "echo \"started \$1\" >> \$log\n" +
                    "while [ ! -e \"\$PLAY1_STOP_FILE\" ]; do sleep 0.05; done\n" +
                    "if listening; then echo play-listening-at-stop >> \$log; fi\n" +
                    "rm -f \"\$PLAY1_STOP_FILE\"\n" +
                    "echo stopped-by-file >> \$log\n"
            )
            setExecutable(true)
        }
    }

    /** A bundle carrying Nuxt's server whose framework jar listens like Play, and a stub directory with the stub node. */
    private fun ssrBundle(tmp: File, conf: String = "application.name=testapp\n"): Pair<File, File> {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        listeningFrameworkJar(File(tmp, "fake"), File(bundle, "framework/play-$fwVersion.jar"))
        File(bundle, ".classpath").writeText("framework/play-$fwVersion.jar\n")
        File(bundle, "conf").mkdirs()
        File(bundle, "conf/application.conf").writeText(conf)
        File(bundle, "frontend/.output/server").mkdirs()
        File(bundle, "frontend/.output/server/index.mjs").writeText("// Nuxt's server")
        File(bundle, "frontend/.output/play1-server.mjs").writeText("// started by the stub node, which never reads it")
        File(bundle, "frontend/.output/play1.json").writeText("{\"node\": \"$nodeRange\"}\n")
        val stubs = File(tmp, "bin").apply { mkdirs() }
        writeStubNode(stubs)
        return bundle to stubs
    }

    private fun nodeLog(bundle: File): List<String> =
        File(bundle, "node-stub.log").takeIf { it.isFile }?.readLines()?.map { it.trimEnd('\r') } ?: emptyList()

    /** Wait up to 30 s for the stub node to log a line starting with [line]. */
    private fun awaitNodeLog(bundle: File, line: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (nodeLog(bundle).none { it.startsWith(line) } && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(nodeLog(bundle).any { it.startsWith(line) }, "the stub node never logged '$line': ${nodeLog(bundle)}")
    }

    private fun listening(port: Int) = runCatching { Socket("127.0.0.1", port).close() }.isSuccess

    /** Stop whatever a failed test left running, so no JVM or stub outlives it. */
    private fun stopLeftovers(bundle: File, stubs: File) {
        if (File(bundle, "server.pid").exists() || File(bundle, "server.frontend.pid").exists()) launch(bundle, stubs, "stop")
    }

    @Test
    fun `start runs Nuxt's server once Play listens, pointed at it, and stop stops it first`(@TempDir tmp: File) {
        val (bundle, stubs) = ssrBundle(tmp, "application.name=testapp\nfrontend.port=3456\n")
        File(bundle, "certs").mkdirs()
        File(bundle, "certs/.env").writeText("PF212_FROM_DOTENV=from certs/.env\n")
        val port = freePort()
        try {
            // Play takes 1.5 s to listen, so Nuxt's server can only find it listening by waiting.
            val start = launch(bundle, stubs, "start", "--http.port=$port", "-Dpf212.listen.delay=1500", path = javaBin)

            assertEquals(0, start.exit, "${start.stdout}\n${start.stderr}")
            assertTrue(start.stdout.any { it.startsWith("~ OK, Nuxt's server is started") }, "${start.stdout}")
            awaitNodeLog(bundle, "started")
            val log = nodeLog(bundle)
            assertEquals("check frontend/.output/play1-server.mjs", log.first(), "the Node check comes before anything starts: $log")
            assertTrue("play-listening-at-start" in log, "Nuxt's server started before Play listened: $log")
            for (env in listOf(
                "NITRO_PORT=3456", "NITRO_HOST=127.0.0.1", "NUXT_PLAY_BACKEND_URL=http://127.0.0.1:$port",
                "PLAY1_STOP_FILE=server.frontend.pid.stop", "PF212_FROM_DOTENV=from certs/.env",
            )) assertTrue("ENV:$env" in log, "Nuxt's server should get $env: $log")

            val status = launch(bundle, stubs, "status")
            assertEquals(0, status.exit, "${status.stdout}")
            assertEquals(listOf("Running (pid: ", "Nuxt's server running (pid: "), status.stdout.map { it.substringBefore("pid: ") + "pid: " })
            assertEquals(
                listOf(File(bundle, "server.pid"), File(bundle, "server.frontend.pid")).map { it.readText().trim() },
                launch(bundle, stubs, "pid").stdout,
            )

            val stop = launch(bundle, stubs, "stop")

            assertEquals(0, stop.exit, "${stop.stdout}")
            val nuxtStopped = stop.stdout.indexOf("~ OK, Nuxt's server is stopped")
            val playStopped = stop.stdout.indexOfFirst { it.startsWith("~ OK, ") && it.endsWith(" is stopped") && "Nuxt" !in it }
            assertTrue(nuxtStopped in 0 until playStopped, "Nuxt's server stops first, then Play: ${stop.stdout}")
            assertTrue("play-listening-at-stop" in nodeLog(bundle), "Play went down before Nuxt's server: ${nodeLog(bundle)}")
            assertTrue("stopped-by-file" in nodeLog(bundle), "${nodeLog(bundle)}")
            for (left in listOf("server.pid", "server.frontend.pid", "server.frontend.pid.stop")) {
                assertFalse(File(bundle, left).exists(), "$left should be gone after stop")
            }
            val after = launch(bundle, stubs, "status")
            assertEquals(1, after.exit)
            assertEquals(listOf("Not running", "Nuxt's server not running"), after.stdout)
        } finally {
            stopLeftovers(bundle, stubs)
        }
    }

    @Test
    fun `start refuses before starting anything when Node is missing or too old`(@TempDir tmp: File) {
        val (bundle, stubs) = ssrBundle(tmp)
        val port = freePort()
        try {
            val missing = launch(bundle, stubs, "start", "--http.port=$port", env = mapOf("PLAY_NODE" to "no-such-node"), path = javaBin)
            assertEquals(1, missing.exit, "${missing.stdout}")
            assertTrue(missing.stderr.any { "needs Node $nodeRange" in it && "PLAY_NODE names 'no-such-node'" in it }, "${missing.stderr}")
            assertFalse(File(bundle, "server.pid").exists(), "Play must not have been started")

            val old = launch(bundle, stubs, "start", "--http.port=$port", env = mapOf("STUB_NODE_TOO_OLD" to "1"), path = javaBin)
            assertEquals(1, old.exit, "${old.stdout}")
            assertTrue(old.stderr.any { "needs Node ^99.0.0; this is Node 1.0.0" in it }, "${old.stderr}")
            assertFalse(File(bundle, "server.pid").exists(), "Play must not have been started")
            assertEquals(listOf("check frontend/.output/play1-server.mjs"), nodeLog(bundle), "nothing but the check ran")
            assertFalse(listening(port))
        } finally {
            stopLeftovers(bundle, stubs)
        }
    }

    @Test
    fun `a bundle without Nuxt's server starts and reports as before and never runs node`(@TempDir tmp: File) {
        val (bundle, stubs) = bundleWithConf(tmp, "application.name=testapp\n")
        writeStubNode(stubs)

        val start = launch(bundle, stubs, "start")

        assertEquals(0, start.exit, "${start.stdout}\n${start.stderr}")
        assertTrue(start.stdout.none { "Nuxt" in it }, "${start.stdout}")
        assertFalse(File(bundle, "server.frontend.pid").exists())
        assertEquals(emptyList<String>(), nodeLog(bundle), "node must not run for a bundle without Nuxt's server")
        // One line, as before PF-212, whether or not the stub java has exited yet.
        val status = launch(bundle, stubs, "status").stdout
        assertEquals(1, status.size, "$status")
        assertTrue(status.single().startsWith("Running (pid: ") || status.single() == "Not running", "$status")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Process.destroy() is TerminateProcess there, which no shell can trap")
    fun `run supervises both, and a SIGTERM for the launcher stops Nuxt's server and Play`(@TempDir tmp: File) {
        val (bundle, stubs) = ssrBundle(tmp)
        val port = freePort()
        val out = File(tmp, "run.out")
        val run = launcher("run", "--http.port=$port", "-Dpf212.listen.delay=1500")
            .directory(bundle)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .pathFirst(stubs, *javaBin.toTypedArray())
            .start()
        try {
            awaitNodeLog(bundle, "started")
            assertTrue("play-listening-at-start" in nodeLog(bundle), "${nodeLog(bundle)}")

            run.destroy() // SIGTERM, for the launcher alone: its children ignore SIGINT and get no signal from here

            assertTrue(run.waitFor(40, TimeUnit.SECONDS), "the launcher did not exit:\n${out.readText()}")
            assertEquals(143, run.exitValue(), "the launcher exits with Play's status after its SIGTERM:\n${out.readText()}")
            assertTrue("stopped-by-file" in nodeLog(bundle), "${nodeLog(bundle)}")
            assertFalse(listening(port), "Play is still listening")
            assertTrue("SHUTDOWN HOOK" in out.readText(), "Play was not stopped gracefully:\n${out.readText()}")
        } finally {
            run.destroyForcibly()
        }
    }

    /**
     * A framework jar whose play.server.Server listens on --http.port and answers
     * every request with 200 until the JVM is told to stop -- what `aot-gen` needs
     * of an application. With -Dpf180.exit=true it reports its AOT cache option
     * and returns instead.
     */
    private fun listeningFrameworkJar(work: File, dest: File) = frameworkJar(
        work, dest,
        """
        package play.server;
        import java.io.BufferedReader;
        import java.io.IOException;
        import java.io.InputStreamReader;
        import java.lang.management.ManagementFactory;
        import java.net.ServerSocket;
        import java.net.Socket;
        public class Server {
            public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
            public static void main(String[] args) throws Exception {
                if (Boolean.getBoolean("pf180.exit")) {
                    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                        if (arg.startsWith("-XX:AOTCache=")) System.out.println("STARTED WITH " + arg);
                    }
                    return;
                }
                int port = 0;
                for (String arg : args) {
                    if (arg.startsWith("--http.port=")) port = Integer.parseInt(arg.substring("--http.port=".length()));
                }
                Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("SHUTDOWN HOOK")));
                // PF-212: slow to listen on request, so a test can tell waiting for Play from
                // racing it. Off unless set.
                Thread.sleep(Long.getLong("pf212.listen.delay", 0));
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

    /**
     * A framework jar real enough for the launcher to start: a loadable -javaagent
     * whose play.server.Server prints what the JVM was given and exits.
     */
    private fun reportingFrameworkJar(work: File, dest: File) = frameworkJar(
        work, dest,
        """
        package play.server;
        import java.io.File;
        public class Server {
            public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
            public static void main(String[] args) throws Exception {
                System.out.println("APP=" + new File(System.getProperty("application.path")).getCanonicalPath());
                System.out.println("FRAMEWORK=" + new File(System.getProperty("framework.path")).getCanonicalPath());
                System.out.println("SECOND_JAR_ON_CLASSPATH=" + (Server.class.getResource("/marker.txt") != null));
                System.out.println("VECTOR=" + ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
                System.out.println("FROM_CONF=" + System.getProperty("pf183.from.conf"));
                System.out.println("FROM_CLI=" + System.getProperty("pf183.from.cli"));
                System.out.println("ENV_FILE=" + System.getenv("PF184_FILE"));
                System.out.println("ENV_BOTH=" + System.getenv("PF184_BOTH"));
                System.out.println("ARGS=" + String.join(" ", args));
            }
        }
        """.trimIndent()
    )

    /** Compile [serverSource] as play.server.Server into a jar at [dest] that also loads as a -javaagent. */
    private fun frameworkJar(work: File, dest: File, serverSource: String) {
        val source = File(work, "src/play/server/Server.java").apply {
            parentFile.mkdirs()
            writeText(serverSource)
        }
        val classes = File(work, "classes").apply { mkdirs() }
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK, not a JRE")
        assertEquals(0, javac.run(null, null, null, "-d", classes.absolutePath, source.absolutePath), "compiling the fake framework failed")
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
}
