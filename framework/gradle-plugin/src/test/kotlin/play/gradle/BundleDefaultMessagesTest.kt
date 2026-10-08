package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile
import javax.tools.ToolProvider

/**
 * PF-181: the bundle launcher starts the JVM with -Dframework.path=<bundle>/framework, so
 * MessagesPlugin looks for the framework's default messages at
 * <bundle>/framework/resources/messages. playBundle shipped only the framework jars, the
 * lookup logged "Default messages file missing" and every validation.* / since.* text
 * rendered as its raw key in a bundled app.
 *
 * playBundle depends on playPrecompile, which forks the framework, so the framework here is
 * a fake whose play.server.Server returns at once.
 */
// Same reservation as JulBridgeArgsTest: a JVM forked from TestKit is unverified on Windows,
// where a jar it still holds can fail @TempDir cleanup. The zip step under test is OS-neutral.
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks a JVM from TestKit; unverified on Windows")
class BundleDefaultMessagesTest {

    private val fwVersion = "9.9.9-TEST"
    private val messagesEntry = "testapp/framework/resources/messages"

    @Test
    fun `bundle ships the framework default messages under its framework path`(@TempDir tmp: File) {
        val messages = "# Validation messages\nvalidation.required=Required\nsince.format  = MMM d, yyyy\n"

        ZipFile(bundle(tmp, messages)).use { zip ->
            val entry = zip.getEntry(messagesEntry)
            assertNotNull(entry, "PF-181: the bundle has no $messagesEntry:\n${names(zip)}")
            assertEquals(messages, zip.getInputStream(entry).bufferedReader().use { it.readText() })
        }
    }

    @Test
    fun `a framework without a default messages file still bundles`(@TempDir tmp: File) {
        ZipFile(bundle(tmp, messages = null)).use { zip ->
            assertNull(zip.getEntry(messagesEntry), names(zip))
            // Sanity that this is a real bundle, not an empty zip passing the line above.
            assertNotNull(zip.getEntry("testapp/framework/play-$fwVersion.jar"), names(zip))
        }
    }

    private fun names(zip: ZipFile): String = zip.entries().asSequence().map { it.name }.sorted().joinToString("\n")

    /** Run playBundle against a fake framework holding [messages] (none when null); returns the zip. */
    private fun bundle(tmp: File, messages: String?): File {
        val fw = fakeFramework(File(tmp, "framework-dist"))
        if (messages != null) {
            File(fw, "resources/messages").apply { parentFile.mkdirs(); writeText(messages) }
        }
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
        // playBundle lists the app's sources with `git ls-files`, so the app must be a repo;
        // the ignore file keeps the running build's own state out of that listing.
        File(app, ".gitignore").writeText(".gradle/\nbuild/\n")
        val git = ProcessBuilder("git", "init", "-q").directory(app).inheritIO().start()
        assertEquals(0, git.waitFor(), "git init failed in $app")

        TestProject.runner(app, "playBundle").build()
        return File(app, "dist/testapp-bundle.zip")
    }

    /** A framework distribution just real enough for playPrecompile: a loadable -javaagent jar. */
    private fun fakeFramework(root: File): File {
        val src = File(root.parentFile, "fake-src")
        val classes = File(root.parentFile, "fake-classes").apply { mkdirs() }
        fun source(path: String, body: String) =
            File(src, path).apply { parentFile.mkdirs(); writeText(body) }
        val sources = listOf(
            source("fake/Agent.java", """
                package fake;
                public class Agent {
                    public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
                }
            """.trimIndent()),
            source("play/server/Server.java", """
                package play.server;
                public class Server {
                    public static void main(String[] args) {}
                }
            """.trimIndent()),
        )
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK, not a JRE")
        val rc = javac.run(null, null, null,
            "--release", "25", "-d", classes.absolutePath, *sources.map { it.absolutePath }.toTypedArray())
        assertEquals(0, rc, "compiling the fake framework failed")

        val jar = File(root, "framework/play-$fwVersion.jar").apply { parentFile.mkdirs() }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Premain-Class")] = "fake.Agent"
        }
        JarOutputStream(jar.outputStream(), manifest).use { out ->
            classes.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(JarEntry(f.relativeTo(classes).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return root
    }
}
