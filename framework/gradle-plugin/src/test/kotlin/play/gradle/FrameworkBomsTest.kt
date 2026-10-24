package play.gradle

import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * PF-193: with play1 { importFrameworkBoms = true } the plugin imports the BOM of each library
 * family framework/lib ships, at the version it ships, so that a member of the family the fork
 * does not ship resolves at the fork's version.
 *
 * The fork ships com.squareup.okhttp3:okhttp-jvm 5.5.1. A file-based Maven repository holds
 * okhttp-bom 5.5.1 and mockwebserver3 5.4.0 and 5.5.1.
 */
class FrameworkBomsTest {

    private val fwVersion = "9.9.9-TEST"

    @Test
    fun `a family member declared without a version resolves at the fork's version`(@TempDir tmp: File) {
        val app = app(tmp, play1 = "importFrameworkBoms = true", dependencies = """implementation("com.squareup.okhttp3:mockwebserver3")""")

        val result = TestProject.runner(app, "printClasspaths").build()

        for ((name, jars) in classpaths(result)) {
            assertEquals(listOf("mockwebserver3-5.5.1.jar"), jars.filter { it.startsWith("mockwebserver3-") }, name)
        }
    }

    @Test
    fun `only an application that opts in gets the BOMs`(@TempDir tmp: File) {
        val dependencies = """implementation("com.squareup.okhttp3:mockwebserver3:5.4.0")"""
        val without = app(File(tmp, "without"), dependencies = dependencies)
        val with = app(File(tmp, "with"), play1 = "importFrameworkBoms = true", dependencies = dependencies)

        for ((app, expected) in listOf(without to "mockwebserver3-5.4.0.jar", with to "mockwebserver3-5.5.1.jar")) {
            for ((name, jars) in classpaths(TestProject.runner(app, "printClasspaths").build())) {
                assertEquals(listOf(expected), jars.filter { it.startsWith("mockwebserver3-") }, "${app.parentFile.name}: $name")
            }
        }
    }

    @Test
    fun `a family whose anchor the application manages gets no BOM`(@TempDir tmp: File) {
        val app = app(
            tmp,
            play1 = """
                importFrameworkBoms = true
                appManagedLibraries("com.squareup.okhttp3:okhttp-jvm")
            """.trimIndent(),
            dependencies = """implementation("com.squareup.okhttp3:mockwebserver3:5.4.0")"""
        )

        for ((name, jars) in classpaths(TestProject.runner(app, "printClasspaths").build())) {
            assertEquals(listOf("mockwebserver3-5.4.0.jar"), jars.filter { it.startsWith("mockwebserver3-") }, name)
        }
    }

    // The BOM of a family is imported at the version of its anchor: a family whose anchor the
    // fork stops shipping, or renames, would quietly lose its BOM.
    @Test
    fun `every anchor is a module framework lib ships`() {
        val repoRoot = File("../..").canonicalFile
        assumeTrue(File(repoRoot, "framework/lib-coordinates.properties").isFile, "ant jar writes lib-coordinates.properties")

        val shipped = readLibCoordinates(repoRoot).map { it.module }.toSet()

        assertEquals(emptyList<String>(), FRAMEWORK_BOMS.map { it.first }.filter { it !in shipped })
    }

    /** The jar names on main's compile and runtime classpaths and on test's compile classpath. */
    private fun classpaths(result: BuildResult): Map<String, List<String>> {
        val printed = result.output.lines().filter { it.matches(Regex("(compile|runtime|testCompile)=.*")) }
            .associate { it.substringBefore('=') to it.substringAfter('=').split(',').filter(String::isNotEmpty) }
        assertTrue(printed.size == 3, "printClasspaths printed ${printed.keys}")
        return printed
    }

    private fun app(tmp: File, play1: String = "", dependencies: String): File {
        val fw = File(tmp, "framework-dist")
        jar(File(fw, "framework/play-$fwVersion.jar"))
        jar(File(fw, "framework/lib/okhttp-jvm-5.5.1.jar"))
        File(fw, "framework/lib-coordinates.properties").writeText("okhttp-jvm-5.5.1.jar=com.squareup.okhttp3:okhttp-jvm:5.5.1\n")

        val repo = File(tmp, "repo")
        val bom = File(repo, "com/squareup/okhttp3/okhttp-bom/5.5.1").apply { mkdirs() }
        File(bom, "okhttp-bom-5.5.1.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>com.squareup.okhttp3</groupId>" +
                "<artifactId>okhttp-bom</artifactId><version>5.5.1</version><packaging>pom</packaging>" +
                "<dependencyManagement><dependencies><dependency><groupId>com.squareup.okhttp3</groupId>" +
                "<artifactId>mockwebserver3</artifactId><version>5.5.1</version></dependency></dependencies>" +
                "</dependencyManagement></project>\n"
        )
        for (version in listOf("5.4.0", "5.5.1")) {
            val dir = File(repo, "com/squareup/okhttp3/mockwebserver3/$version")
            jar(File(dir, "mockwebserver3-$version.jar"))
            File(dir, "mockwebserver3-$version.pom").writeText(
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.squareup.okhttp3</groupId>" +
                    "<artifactId>mockwebserver3</artifactId><version>$version</version></project>\n"
            )
        }

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
            |    val main = project.the<SourceSetContainer>()["main"]
            |    val test = project.the<SourceSetContainer>()["test"].compileClasspath
            |    val compile = main.compileClasspath
            |    val runtime = main.runtimeClasspath
            |    doLast {
            |        println("compile=" + compile.files.map { it.name }.joinToString(","))
            |        println("runtime=" + runtime.files.map { it.name }.joinToString(","))
            |        println("testCompile=" + test.files.map { it.name }.joinToString(","))
            |    }
            |}
            """.trimMargin() + "\n"
        )
        return app
    }

    private fun jar(dest: File) {
        dest.parentFile.mkdirs()
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        JarOutputStream(dest.outputStream(), manifest).close()
    }
}
