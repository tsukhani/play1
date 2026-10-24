package play.gradle

import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * PF-192: framework/lib is the only copy of each library the fork ships. The plugin adds
 * framework/lib as plain files, which carry no coordinates, so Gradle used to resolve an
 * application's own copy of the same library beside it, and that copy loaded first.
 * lib-coordinates.properties names each jar's module, and the plugin excludes those modules
 * from the application's graph, except one listed in play1 { appManagedLibraries }, for which
 * the fork's jar is left out instead.
 *
 * The fork here ships com.example:shared 1.5. A file-based Maven repository holds shared 1.0
 * and 2.0, and consumer 1.0, which depends on shared 1.0 and on other 1.0.
 */
class OneCopyPerLibraryTest {

    private val fwVersion = "9.9.9-TEST"

    @Test
    fun `a library the fork ships reaches the classpaths only from framework lib`(@TempDir tmp: File) {
        val app = app(tmp, dependencies = """implementation("com.example:consumer:1.0")""")

        val result = TestProject.runner(app, "playClasspath", "printClasspaths").build()

        for ((name, jars) in classpaths(result)) {
            assertEquals(listOf("shared-1.5.jar"), jars.filter { it.startsWith("shared-") }, "$name holds the fork's copy alone")
            assertTrue("consumer-1.0.jar" in jars && "other-1.0.jar" in jars, "$name keeps the rest of the graph: $jars")
            assertTrue("vendored.jar" in jars, "$name keeps framework/lib's jar without coordinates: $jars")
        }
        assertFalse("has no effect" in result.output, "a transitive copy is not the application's declaration")
    }

    @Test
    fun `a declaration of a library the fork ships warns and loses to framework lib`(@TempDir tmp: File) {
        val app = app(tmp, dependencies = """implementation("com.example:shared:2.0")""")

        val result = TestProject.runner(app, "playClasspath", "printClasspaths").build()

        for ((name, jars) in classpaths(result)) {
            assertEquals(listOf("shared-1.5.jar"), jars.filter { it.startsWith("shared-") }, "$name holds the fork's copy alone")
        }
        val warnings = result.output.lines().filter { "has no effect" in it }
        assertEquals(1, warnings.size, "one warning per declaration: $warnings")
        assertTrue(
            "implementation(\"com.example:shared:2.0\") has no effect" in warnings.single() &&
                "com.example:shared 1.5 from framework/lib" in warnings.single(),
            "the warning names the declaration and the version it lost to: ${warnings.single()}"
        )
    }

    @Test
    fun `an application-managed library replaces the fork's jar on every classpath`(@TempDir tmp: File) {
        val app = app(
            tmp,
            play1 = """appManagedLibraries("com.example:shared", "com.example:unshipped")""",
            dependencies = """
                implementation("com.example:shared:2.0")
                implementation("com.example:consumer:1.0")
            """.trimIndent()
        )

        val result = TestProject.runner(app, "playClasspath", "printClasspaths").build()

        for ((name, jars) in classpaths(result)) {
            assertEquals(listOf("shared-2.0.jar"), jars.filter { it.startsWith("shared-") }, "$name holds the application's copy alone")
            assertTrue("vendored.jar" in jars, "only the managed library's jar leaves: $jars")
        }
        assertTrue(
            "~ com.example:shared is application-managed: 2.0 in place of the fork's 1.5 " +
                "(framework/lib/shared-1.5.jar left off every classpath)" in result.output,
            "configure logs the opt-out with both versions"
        )
        assertTrue(
            "play1 { appManagedLibraries } lists com.example:unshipped, which framework/lib does not ship" in result.output,
            "an opt-out that matches nothing is reported"
        )
        assertFalse("has no effect" in result.output, "a managed library's declaration takes effect")
    }

    // playBundle depends on playPrecompile, which forks the fake framework; a JVM forked from
    // TestKit is unverified on Windows, as in BundleClasspathTest.
    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "forks a JVM from TestKit; unverified on Windows")
    fun `a bundle ships one copy of each library`(@TempDir tmp: File) {
        val forked = app(File(tmp, "forked"), dependencies = """implementation("com.example:consumer:1.0")""", forksJvm = true)
        val managed = app(
            File(tmp, "managed"),
            play1 = """appManagedLibraries("com.example:shared")""",
            dependencies = """implementation("com.example:shared:2.0")""",
            forksJvm = true
        )

        for ((app, expected) in listOf(forked to "framework/lib/shared-1.5.jar", managed to "lib/shared-2.0.jar")) {
            TestProject.runner(app, "playBundle").build()

            ZipFile(File(app, "dist/testapp-bundle.zip")).use { zip ->
                val shipped = zip.entries().asSequence().map { it.name.removePrefix("testapp/") }
                    .filter { it.endsWith(".jar") && File(it).name.startsWith("shared-") }.toList()
                assertEquals(listOf(expected), shipped, "${app.parentFile.name}: one copy across lib/ and framework/lib/")
                val classpath = zip.getInputStream(zip.getEntry("testapp/.classpath")).bufferedReader().readLines()
                assertEquals(listOf(expected), classpath.filter { File(it).name.startsWith("shared-") }, "${app.parentFile.name}: .classpath")
            }
        }
    }

    /** The jar names on playClasspath, main's compile classpath and test's, by classpath. */
    private fun classpaths(result: BuildResult): Map<String, List<String>> {
        val lines = result.output.lines()
        val printed = lines.filter { it.startsWith("compile=") || it.startsWith("testCompile=") }
            .associate { it.substringBefore('=') to it.substringAfter('=').split(',').filter(String::isNotEmpty) }
        val playClasspath = lines.filter { it.endsWith(".jar") && File(it).isAbsolute }.map { File(it).name }
        assertEquals(2, printed.size, "printClasspaths printed nothing")
        assertTrue(playClasspath.isNotEmpty(), "playClasspath printed nothing")
        return printed + ("playClasspath" to playClasspath)
    }

    private fun app(tmp: File, play1: String = "", dependencies: String, forksJvm: Boolean = false): File {
        val fw = File(tmp, "framework-dist")
        if (forksJvm) fakeFramework(fw) else jar(File(fw, "framework/play-$fwVersion.jar"))
        jar(File(fw, "framework/lib/shared-1.5.jar"))
        jar(File(fw, "framework/lib/vendored.jar"))
        File(fw, "framework/lib-coordinates.properties").writeText(
            "# written by ant jar\nshared-1.5.jar=com.example:shared:1.5\nvendored.jar=\n"
        )

        val repo = File(tmp, "repo")
        publish(repo, "com.example", "shared", "1.0")
        publish(repo, "com.example", "shared", "2.0")
        publish(repo, "com.example", "other", "1.0")
        publish(repo, "com.example", "consumer", "1.0", "com.example:shared:1.0", "com.example:other:1.0")

        val app = File(tmp, "app").apply { mkdirs() }
        TestProject.write(
            app,
            play1Block = """
                frameworkVersion.set("$fwVersion")
                frameworkPath.set(file("${fw.absolutePath.replace("\\", "\\\\")}"))
                $play1
            """.trimIndent()
        )
        File(app, "build.gradle.kts").appendText(
            """
            |repositories {
            |    maven { url = uri("${repo.toURI()}") }
            |}
            |dependencies {
            |${dependencies.prependIndent("    ")}
            |}
            |tasks.register("printClasspaths") {
            |    val main = project.the<SourceSetContainer>()["main"].compileClasspath
            |    val test = project.the<SourceSetContainer>()["test"].compileClasspath
            |    doLast {
            |        println("compile=" + main.files.map { it.name }.joinToString(","))
            |        println("testCompile=" + test.files.map { it.name }.joinToString(","))
            |    }
            |}
            """.trimMargin() + "\n"
        )
        if (forksJvm) {
            File(app, "conf").mkdirs()
            File(app, "conf/routes").writeText("")
            File(app, "conf/application.conf").writeText("application.name=testapp\n")
            // playBundle lists the app's sources with `git ls-files`.
            File(app, ".gitignore").writeText(".gradle/\nbuild/\nmodules/\n")
            val git = ProcessBuilder("git", "init", "-q").directory(app).inheritIO().start()
            assertEquals(0, git.waitFor(), "git init failed in $app")
        }
        return app
    }

    /** A module in a Maven layout under [repo], with a POM naming [dependencies] ("g:a:v"). */
    private fun publish(repo: File, group: String, artifact: String, version: String, vararg dependencies: String) {
        val dir = File(repo, "${group.replace('.', '/')}/$artifact/$version")
        jar(File(dir, "$artifact-$version.jar"))
        val deps = dependencies.joinToString("") { dep ->
            val (g, a, v) = dep.split(':')
            "<dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></dependency>"
        }
        File(dir, "$artifact-$version.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId>" +
                "<version>$version</version><dependencies>$deps</dependencies></project>\n"
        )
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
    private fun fakeFramework(root: File) {
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
    }
}
