package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
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
     */
    private fun writeStubs(dir: File, windows: Boolean) {
        dir.mkdirs()
        fun stub(name: String, body: String) =
            File(dir, name).apply { writeText("#!/bin/bash\n$body\n"); setExecutable(true) }

        stub("java", "printf '%s\\n' \"\$@\"")
        if (windows) {
            stub("uname", "echo MINGW64_NT-10.0-19045")
            // Real cygpath -w maps /x/y to a drive-lettered, backslashed path;
            // the drive it picks is irrelevant here, the shape is what we assert.
            stub("cygpath", "echo \"C:\$(echo \"\$2\" | tr '/' '\\\\')\"")
        }
    }

    /** Run `./play run` in [bundle] with [stubs] prepended to PATH; return java's argv. */
    private fun launcherArgv(bundle: File, stubs: File): List<String> {
        val proc = ProcessBuilder("./play", "run")
            .directory(bundle)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${stubs.absolutePath}${File.pathSeparator}${System.getenv("PATH")}" }
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "launcher should exit cleanly\n$out")
        return out.lines().filter { it.isNotEmpty() }
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
}
