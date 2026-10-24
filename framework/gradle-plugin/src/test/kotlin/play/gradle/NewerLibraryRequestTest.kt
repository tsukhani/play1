package play.gradle

import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * PF-194: the plugin excludes each library framework/lib ships from the application's graph
 * (PF-192), and the version a dependency asked for went with the excluded edge, so a dependency
 * built against a newer version ran on the fork's older copy without a word. The plugin now warns
 * once per library that a dependency asks for above the fork's version.
 *
 * The fork ships com.example:shared 1.5 and com.example:helper 1.0. A file-based Maven repository
 * holds shared 1.0, 1.5, 2.0 (which depends on helper 2.0) and 2.5, and one consumer per kind of
 * request.
 */
class NewerLibraryRequestTest {

    private val fwVersion = "9.9.9-TEST"

    @Test
    fun `a newer transitive request warns once, naming every requester`(@TempDir tmp: File) {
        val app = app(tmp, dependencies = """
            implementation("com.example:newer:1.0")
            testImplementation("com.example:newer-too:1.0")
        """.trimIndent())

        val result = TestProject.runner(app, "help").build()

        // helper 2.0 is asked for only by shared 2.0, which framework/lib replaces: not reported.
        assertEquals(
            listOf(
                "~ WARNING: com.example:newer-too:1.0 and com.example:newer:1.0 ask for com.example:shared 2.0, " +
                    "but framework/lib ships 1.5, which they run on. Upgrade the framework's copy, or add " +
                    "\"com.example:shared\" to play1 { appManagedLibraries(...) } to keep the application's version."
            ),
            requestWarnings(result)
        )
    }

    @Test
    fun `an equal or older request and a range the fork's version satisfies do not warn`(@TempDir tmp: File) {
        val app = app(tmp, dependencies = """
            implementation("com.example:older:1.0")
            implementation("com.example:equal:1.0")
            implementation("com.example:ranged-ok:1.0")
        """.trimIndent())

        val result = TestProject.runner(app, "help").build()

        assertEquals(emptyList<String>(), requestWarnings(result))
    }

    @Test
    fun `a range the fork's version is outside of warns`(@TempDir tmp: File) {
        val app = app(tmp, dependencies = """implementation("com.example:ranged-high:1.0")""")

        val result = TestProject.runner(app, "help").build()

        val warning = requestWarnings(result).single()
        assertTrue(
            warning.startsWith("~ WARNING: com.example:ranged-high:1.0 asks for com.example:shared [2.0,3.0), but framework/lib ships 1.5, which it runs on."),
            warning
        )
    }

    @Test
    fun `an application-managed library is not checked, but its own requests are`(@TempDir tmp: File) {
        val app = app(
            tmp,
            play1 = """appManagedLibraries("com.example:shared")""",
            dependencies = """implementation("com.example:newer:1.0")"""
        )

        val result = TestProject.runner(app, "help").build()

        // shared 2.0 now reaches the classpaths, and with it its request for helper 2.0, which
        // framework/lib still provides at 1.0.
        assertEquals(
            listOf("com.example:shared:2.0 asks for com.example:helper 2.0"),
            requestWarnings(result).map { it.removePrefix("~ WARNING: ").substringBefore(",") }
        )
    }

    @Test
    fun `failOnNewerLibraryRequests turns the warning into a failure`(@TempDir tmp: File) {
        val byProperty = app(File(tmp, "property"), dependencies = """implementation("com.example:newer:1.0")""")
        val byDsl = app(
            File(tmp, "dsl"),
            play1 = "failOnNewerLibraryRequests = true",
            dependencies = """implementation("com.example:newer:1.0")"""
        )

        for (result in listOf(
            TestProject.runner(byProperty, "help", "-PfailOnNewerLibraryRequests=true").buildAndFail(),
            TestProject.runner(byDsl, "help").buildAndFail(),
        )) {
            assertTrue(
                "failOnNewerLibraryRequests is on:" in result.output &&
                    "com.example:newer:1.0 asks for com.example:shared 2.0" in result.output,
                "the failure names the request"
            )
        }
        // The property wins over the build script for one build.
        TestProject.runner(byDsl, "help", "-PfailOnNewerLibraryRequests=false").build()
    }

    @Test
    fun `the warning comes with the build that adds the request, under the configuration cache`(@TempDir tmp: File) {
        val app = app(tmp, dependencies = """implementation("com.example:older:1.0")""")
        val first = TestProject.runner(app, "help", "--configuration-cache").build()
        assertEquals(emptyList<String>(), requestWarnings(first))

        val build = File(app, "build.gradle.kts")
        build.writeText(build.readText().replace("com.example:older:1.0", "com.example:newer:1.0"))
        val second = TestProject.runner(app, "help", "--configuration-cache").build()

        assertEquals(1, requestWarnings(second).size, "the changed build script reconfigures and warns")
        assertTrue("Configuration cache entry stored" in second.output, "no configuration cache problem")
    }

    private fun requestWarnings(result: BuildResult) =
        result.output.lines().filter { it.startsWith("~ WARNING:") && "but framework/lib ships" in it }

    private fun app(tmp: File, play1: String = "", dependencies: String): File {
        val fw = File(tmp, "framework-dist")
        jar(File(fw, "framework/play-$fwVersion.jar"))
        jar(File(fw, "framework/lib/shared-1.5.jar"))
        jar(File(fw, "framework/lib/helper-1.0.jar"))
        File(fw, "framework/lib-coordinates.properties").writeText(
            "shared-1.5.jar=com.example:shared:1.5\nhelper-1.0.jar=com.example:helper:1.0\n"
        )

        val repo = File(tmp, "repo")
        publish(repo, "com.example", "helper", "2.0")
        publish(repo, "com.example", "shared", "1.0")
        publish(repo, "com.example", "shared", "1.5")
        publish(repo, "com.example", "shared", "2.0", "com.example:helper:2.0")
        publish(repo, "com.example", "shared", "2.5")
        publish(repo, "com.example", "newer", "1.0", "com.example:shared:2.0")
        publish(repo, "com.example", "newer-too", "1.0", "com.example:shared:2.0")
        publish(repo, "com.example", "older", "1.0", "com.example:shared:1.0")
        publish(repo, "com.example", "equal", "1.0", "com.example:shared:1.5")
        publish(repo, "com.example", "ranged-ok", "1.0", "com.example:shared:[1.0,2.0)")
        publish(repo, "com.example", "ranged-high", "1.0", "com.example:shared:[2.0,3.0)")

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
            """.trimMargin() + "\n"
        )
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

    private fun jar(dest: File) {
        dest.parentFile.mkdirs()
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        JarOutputStream(dest.outputStream(), manifest).close()
    }
}
