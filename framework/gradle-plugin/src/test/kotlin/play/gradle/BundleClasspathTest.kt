package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
 * PF-180: the JDK refuses to write an AOT cache while a non-empty directory is on the
 * classpath, and playBundle used to list conf/ as the first .classpath entry. The bundle's
 * classpath is jars only now; conf/ still ships and is read as files -- by Play itself, and
 * through ApplicationClassloader, which has conf/ on its javaPath.
 *
 * playBundle depends on playPrecompile, which forks the framework, so the framework here is
 * a fake whose play.server.Server returns at once.
 */
// Same reservation as BundleDefaultMessagesTest: a JVM forked from TestKit is unverified on
// Windows. The .classpath step under test is OS-neutral.
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks a JVM from TestKit; unverified on Windows")
class BundleClasspathTest {

    private val fwVersion = "9.9.9-TEST"

    @Test
    fun `every classpath entry of a bundle is a jar the bundle ships`(@TempDir tmp: File) {
        val fw = fakeFramework(File(tmp, "framework-dist"))
        // One jar from each place the bundle classpath draws on.
        jar(File(fw, "framework/lib/dependency.jar"))
        jar(File(fw, "modules/somemodule/lib/play-somemodule.jar"))
        File(fw, "modules/somemodule/conf/messages").apply { parentFile.mkdirs(); writeText("hello=world\n") }
        val app = File(tmp, "app").apply { mkdirs() }
        TestProject.write(
            app,
            play1Block = """
                frameworkVersion.set("$fwVersion")
                frameworkPath.set(file("${fw.absolutePath.replace("\\", "\\\\")}"))
                modules("somemodule")
            """.trimIndent()
        )
        jar(File(app, "lib/app-library.jar"))
        File(app, "conf").mkdirs()
        File(app, "conf/routes").writeText("")
        File(app, "conf/application.conf").writeText("application.name=testapp\n")
        // playBundle lists the app's sources with `git ls-files`, so the app must be a repo;
        // the ignore file keeps the running build's own state out of that listing.
        File(app, ".gitignore").writeText(".gradle/\nbuild/\nmodules/\n")
        val git = ProcessBuilder("git", "init", "-q").directory(app).inheritIO().start()
        assertEquals(0, git.waitFor(), "git init failed in $app")

        TestProject.runner(app, "playBundle").build()

        ZipFile(File(app, "dist/testapp-bundle.zip")).use { zip ->
            val entries = zip.getInputStream(zip.getEntry("testapp/.classpath")).bufferedReader().readLines()
            assertEquals(
                listOf(
                    "framework/play-$fwVersion.jar",
                    "framework/lib/dependency.jar",
                    "lib/app-library.jar",
                    "modules/somemodule/lib/play-somemodule.jar"
                ),
                entries,
                "a directory on the classpath (conf/ was the first entry) rules out the AOT cache"
            )
            entries.forEach { entry ->
                val shipped = zip.getEntry("testapp/$entry")
                assertNotNull(shipped, "the bundle lists $entry on its classpath but does not ship it")
                assertTrue(!shipped.isDirectory, "$entry is a directory in the bundle")
            }
            // Off the classpath, not out of the bundle.
            assertNotNull(zip.getEntry("testapp/conf/application.conf"), "conf/ must still ship")
        }
    }

    private fun jar(dest: File, classes: File? = null, manifest: Manifest = Manifest()) {
        dest.parentFile.mkdirs()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        JarOutputStream(dest.outputStream(), manifest).use { out ->
            classes?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                out.putNextEntry(JarEntry(f.relativeTo(classes).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
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

        jar(
            File(root, "framework/play-$fwVersion.jar"), classes,
            Manifest().apply { mainAttributes[Attributes.Name("Premain-Class")] = "fake.Agent" }
        )
        return root
    }
}
