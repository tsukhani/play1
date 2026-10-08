package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PF-171: the bundled `play` launcher (src/main/resources/bundle-play.sh, baked
 * into the zip by PlayBundleTask) has to hand a *native* JVM its arguments even
 * when the shell running it is POSIX — Git Bash / MSYS2 / Cygwin on Windows.
 * java.exe splits -classpath on ';' rather than ':' and cannot resolve
 * '/c/Users/...', so a POSIX-only launcher dies with a NoClassDefFoundError on
 * the first framework dependency (the -javaagent keeps the framework jar itself
 * loadable, which is why the symptom looks like a packaging bug).
 *
 * We can't run Windows here, so we run the real script against stubbed `uname`
 * and `cygpath` on PATH and assert the argv it hands `java` — a stub that just
 * echoes its arguments. That covers the branch itself; the reachable half of a
 * Windows verification.
 */
// The launcher is a #!/bin/bash script driven through ProcessBuilder, so these
// can only run where a POSIX shell executes a shebang. On the Windows CI leg the
// MSYS branch is simulated from macOS/Linux instead -- see the stubs below.
@DisabledOnOs(OS.WINDOWS, disabledReason = "bundle-play.sh needs a POSIX shell to execute")
class BundleLauncherTest {

    private val fwVersion = "1.13.57"

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
        File(dir, ".classpath").writeText("conf\nframework/play-$fwVersion.jar\nframework/lib/netty.jar\n")
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
        val proc = ProcessBuilder("./play", "run", *args)
            .directory(bundle)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${stubs.absolutePath}${File.pathSeparator}${System.getenv("PATH")}" }
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
        val proc = ProcessBuilder("./play", "start", *args)
            .directory(bundle)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${stubs.absolutePath}${File.pathSeparator}${System.getenv("PATH")}" }
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "launcher should exit cleanly\n$out")
        val lines = out.lines()
        val console = lines.indexOfFirst { it.startsWith("~ console output (stdout/stderr) -> ") }
        assertTrue(console >= 0, "start printed no console-output line:\n$out")
        assertEquals(
            File(bundle, "logs/system.out").canonicalPath,
            File(lines[console].substringAfter(" -> ")).canonicalPath
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
     * carriage return that survived a CRLF certs/.env stays visible.
     */
    private fun launch(
        bundle: File,
        stubs: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
        probe: List<String> = emptyList()
    ): Launch {
        val stderr = File(stubs, "stderr.txt")
        val proc = ProcessBuilder("./play", *args)
            .directory(bundle)
            .redirectError(stderr)
            .apply {
                environment()["PATH"] = "${stubs.absolutePath}${File.pathSeparator}${System.getenv("PATH")}"
                // Only [env] is "the host" here: neither a probed name nor the two
                // variables the launcher reads itself may leak in from the
                // developer's shell. Nor UID/EUID: a shell that exports them (the
                // docker-compose idiom) makes them host-defined rather than readonly.
                environment().keys.removeAll(probe + listOf("PLAY_ID", "PLAY_PID_FILE", "UID", "EUID"))
                environment()["STUB_PRINT_ENV"] = probe.joinToString(" ")
                environment().putAll(env)
            }
            .start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        fun lines(text: String) = text.split('\n').filter { it.isNotEmpty() }
        return Launch(exit, lines(stdout), lines(stderr.readText()))
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

    private fun classpathOf(argv: List<String>) = argv[argv.indexOf("-classpath") + 1]

    private fun valueOf(argv: List<String>, prop: String) =
        argv.single { it.startsWith("-D$prop=") }.substringAfter('=')

    @Test
    fun `launcher uses POSIX separator and untranslated paths off Windows`(@TempDir tmp: File) {
        val bundle = File(tmp, "app").apply { mkdirs() }
        writeBundle(bundle)
        val stubs = File(tmp, "bin")
        writeStubs(stubs, windows = false)

        val argv = launcherArgv(bundle, stubs)

        assertEquals(
            "conf:framework/play-$fwVersion.jar:framework/lib/netty.jar",
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
            "conf;framework/play-$fwVersion.jar;framework/lib/netty.jar",
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
    fun `javaagent stays relative on both platforms`(@TempDir tmp: File) {
        listOf(false, true).forEach { windows ->
            val bundle = File(tmp, "app-$windows").apply { mkdirs() }
            writeBundle(bundle)
            val stubs = File(tmp, "bin-$windows")
            writeStubs(stubs, windows)

            val agent = launcherArgv(bundle, stubs).single { it.startsWith("-javaagent:") }
            // Relative, so it resolves against the launcher's own `cd "$SCRIPT_DIR"`
            // — which a Git Bash-spawned native process inherits already translated.
            // Translating it would be a regression, not a fix.
            assertEquals("-javaagent:framework/play-$fwVersion.jar", agent, "windows=$windows")
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
        // PF-183: the command line is the only route for JVM options in a bundle,
        // and the launcher used to forward six forms and drop the rest unannounced.
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

        val proc = ProcessBuilder("/bin/sh", "-c", "./play run --stacktrace 2>&-")
            .directory(bundle)
            .apply { environment()["PATH"] = "${stubs.absolutePath}${File.pathSeparator}${System.getenv("PATH")}" }
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
        assertEquals("conf:framework/play-$fwVersion.jar:framework/lib/netty.jar", classpathOf(run.stdout))
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
}
