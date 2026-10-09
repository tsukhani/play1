package play.gradle

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.tools.ToolProvider

/**
 * A bundle and a dist carry the application's precompiled classes as one jar,
 * precompiled/classes.jar, which the framework reads in place of the precompiled/java tree.
 * The jar is made for the artifact only: `play precompile` and an application run from its
 * working copy keep the tree, and nothing is added to the working copy's precompiled/.
 *
 * The framework here is a fake whose play.server.Server stands in for a precompile run by
 * writing a few "class" files and a template where the real one would.
 */
// Same reservation as BundleClasspathTest: a JVM forked from TestKit is unverified on Windows.
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks a JVM from TestKit; unverified on Windows")
class PrecompiledJarPackagingTest {

    private val fwVersion = "9.9.9-TEST"
    private val classes = listOf("controllers/Application.class", "internal/Dev.class", "models/Note\$Draft.class")

    @Test
    fun `a bundle carries the precompiled classes as one jar and leaves the working copy alone`(@TempDir tmp: File) {
        val app = app(tmp)

        TestProject.runner(app, "playBundle").build()
        val first = precompiledJarOf(File(app, "dist/testapp-bundle.zip"))

        ZipFile(File(app, "dist/testapp-bundle.zip")).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.none { it.startsWith("testapp/precompiled/java/") }, "the tree must not ship beside the jar: $names")
            assertNotNull(
                zip.getEntry("testapp/precompiled/templates/app/views/Application/index.html"),
                "precompiled templates still ship as files"
            )
        }
        val entries = entriesOf(first)
        assertEquals(classes, entries.map { it.first.name }, "every class of the tree, sorted")
        entries.forEach { (entry, bytes) ->
            assertArrayEquals(File(app, "precompiled/java/${entry.name}").readBytes(), bytes, entry.name)
            // Stored: the framework reads every entry at each start, and the zip around the jar compresses it anyway.
            assertEquals(ZipEntry.STORED, entry.method, "${entry.name} must not be deflated")
        }

        // The working copy is as play precompile left it: an application started from it runs off the tree.
        classes.forEach { assertTrue(File(app, "precompiled/java/$it").isFile, "$it must stay in the working copy") }
        assertFalse(File(app, "precompiled/classes.jar").exists(), "no jar is written into the working copy")

        // The same classes give the same jar, byte for byte, whenever it is built.
        Thread.sleep(2100)
        TestProject.runner(app, "playBundle").build()
        assertArrayEquals(first, precompiledJarOf(File(app, "dist/testapp-bundle.zip")), "the jar must be reproducible")
    }

    @Test
    fun `a dist carries the jar too, and distignore still decides which classes ship`(@TempDir tmp: File) {
        val app = app(tmp)
        File(app, ".distignore").writeText("precompiled/java/internal/\n")

        TestProject.runner(app, "playDist").build()

        val dist = File(app, "dist/testapp.zip")
        ZipFile(dist).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.none { it.startsWith("testapp/precompiled/java/") }, "the tree must not ship beside the jar: $names")
        }
        assertEquals(
            listOf("controllers/Application.class", "models/Note\$Draft.class"),
            entriesOf(precompiledJarOf(dist)).map { it.first.name }
        )
    }

    @Test
    fun `a jar the build supplies is shipped as it is`(@TempDir tmp: File) {
        val app = app(tmp)
        // What a ProGuard step leaves behind; any bytes will do, the plugin must not look inside.
        File(app, "processed.jar").writeText("the output of a build step")
        File(app, "build.gradle.kts").appendText(
            """
            tasks.named<play.gradle.PlayBundleTask>("playBundle") {
                precompiledJar.set(layout.projectDirectory.file("processed.jar"))
            }
            """.trimIndent() + "\n"
        )

        TestProject.runner(app, "playBundle").build()

        val bundle = File(app, "dist/testapp-bundle.zip")
        assertEquals("the output of a build step", String(precompiledJarOf(bundle)))
        ZipFile(bundle).use { zip ->
            assertTrue(zip.entries().asSequence().none { it.name.startsWith("testapp/precompiled/java/") })
        }
    }

    @Test
    fun `playPrecompiledJar hands a build step the classes as one jar, outside the working copy's precompiled folder`(@TempDir tmp: File) {
        val app = app(tmp)

        TestProject.runner(app, "playPrecompiledJar").build()

        val jar = File(app, "build/play/precompiled/classes.jar")
        assertTrue(jar.isFile, "the jar belongs under build/, where a ProGuard step can take it as input")
        assertEquals(classes, entriesOf(jar.readBytes()).map { it.first.name })
        assertFalse(File(app, "precompiled/classes.jar").exists(), "no jar is written into the working copy")
    }

    @Test
    fun `a working copy's AOT cache is in neither artifact`(@TempDir tmp: File) {
        // PF-185: `play aot-gen` leaves app.aot in the application directory, and git lists
        // an untracked file wherever .gitignore does not name it. The cache fits the machine
        // it was trained on only; in a bundle it would also be the name the launcher looks for.
        val app = app(tmp)
        File(app, "app.aot").writeText("fits this machine only")
        File(app, "app.aot.new").writeText("a training run in progress")

        TestProject.runner(app, "playDist", "playBundle").build()

        for (artifact in listOf("dist/testapp.zip", "dist/testapp-bundle.zip")) {
            ZipFile(File(app, artifact)).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue("testapp/conf/application.conf" in names, "$artifact: $names")
                assertEquals(emptyList<String>(), names.filter { "app.aot" in it }, artifact)
            }
        }
    }

    private fun precompiledJarOf(artifact: File): ByteArray =
        ZipFile(artifact).use { zip ->
            val entry = zip.getEntry("testapp/precompiled/classes.jar")
            assertNotNull(entry, "${artifact.name} must carry precompiled/classes.jar")
            zip.getInputStream(entry).readBytes()
        }

    private fun entriesOf(jar: ByteArray): List<Pair<ZipEntry, ByteArray>> {
        // Through a file: only the central directory says how an entry is stored.
        val file = File.createTempFile("classes", ".jar").apply { writeBytes(jar); deleteOnExit() }
        return ZipFile(file).use { zip ->
            zip.entries().asSequence().map { it to zip.getInputStream(it).readBytes() }.toList()
        }
    }

    private fun app(tmp: File): File {
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
        File(app, "conf/application.conf").writeText("application.name=testapp\n")
        // The packaging tasks list the app's sources with `git ls-files`, so the app must be a
        // repo. precompiled/ is ignored, as in a real application.
        File(app, ".gitignore").writeText(".gradle/\nbuild/\nmodules/\nprecompiled/\ndist/\n")
        val git = ProcessBuilder("git", "init", "-q").directory(app).inheritIO().start()
        assertEquals(0, git.waitFor(), "git init failed in $app")
        return app
    }

    /** A framework distribution whose precompile run writes three class files and one template. */
    private fun fakeFramework(root: File): File {
        val src = File(root.parentFile, "fake-src")
        val out = File(root.parentFile, "fake-classes").apply { mkdirs() }
        fun source(path: String, body: String) =
            File(src, path).apply { parentFile.mkdirs(); writeText(body) }
        val written = (classes.map { "precompiled/java/$it" } + "precompiled/templates/app/views/Application/index.html")
            .joinToString(", ") { "\"" + it + "\"" }
        val sources = listOf(
            source("fake/Agent.java", """
                package fake;
                public class Agent {
                    public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
                }
            """.trimIndent()),
            source("play/server/Server.java", """
                package play.server;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class Server {
                    public static void main(String[] args) throws Exception {
                        if (System.getProperty("precompile") == null) return;
                        for (String file : new String[] { $written }) {
                            Path path = Path.of(System.getProperty("application.path"), file);
                            Files.createDirectories(path.getParent());
                            Files.writeString(path, "bytecode of " + file);
                        }
                    }
                }
            """.trimIndent()),
        )
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK, not a JRE")
        val rc = javac.run(null, null, null,
            "--release", "25", "-d", out.absolutePath, *sources.map { it.absolutePath }.toTypedArray())
        assertEquals(0, rc, "compiling the fake framework failed")

        val dest = File(root, "framework/play-$fwVersion.jar").apply { parentFile.mkdirs() }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Premain-Class")] = "fake.Agent"
        }
        JarOutputStream(dest.outputStream(), manifest).use { jar ->
            out.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(out).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return root
    }
}
