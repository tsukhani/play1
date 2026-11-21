package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.File

/**
 * PF-229: one class that uses a preview API, compiled by Gradle's compileJava and by Play's own
 * compile in playPrecompile, against the real framework of this checkout, which `ant test`
 * builds before it runs this suite. Both are javac with --enable-preview, from the one
 * java.enablePreview key, so both class files carry the mark the JVM refuses without the flag.
 * Before PF-229 Play's was ECJ's, which compiled the same use to an unmarked class.
 */
class PreviewBothCompilesTest {

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "precompiles in a JVM of the real framework, which no Windows leg has run")
    fun `compileJava and playPrecompile both compile a preview API into a class marked as preview`(@TempDir tmp: File) {
        val repo = File("../..").canonicalFile
        val jar = File(repo, "framework").listFiles { file -> file.name.matches(Regex("play-.+\\.jar")) }?.singleOrNull()
        assumeTrue(jar != null, "ant jar builds framework/play-<version>.jar")
        val version = jar!!.name.removePrefix("play-").removeSuffix(".jar")

        val app = File(tmp, "app").apply { mkdirs() }
        TestProject.write(
            app,
            play1Block = """
                frameworkVersion.set("$version")
                frameworkPath.set(file("${repo.absolutePath.replace("\\", "\\\\")}"))
            """.trimIndent()
        )
        File(app, "conf").mkdirs()
        File(app, "conf/application.conf").writeText(
            "application.name=preview\napplication.mode=dev\napplication.secret=\${PLAY_SECRET}\njava.enablePreview=true\n"
        )
        File(app, "conf/routes").writeText("GET / Application.index\n")
        File(app, "app/controllers").mkdirs()
        File(app, "app/controllers/Application.java").writeText(
            """
            package controllers;
            public class Application extends play.mvc.Controller {
                public static void index() {
                    StableValue<String> value = StableValue.of();
                    renderText(value.orElseSet(() -> "preview"));
                }
            }
            """.trimIndent() + "\n"
        )

        TestProject.runner(app, "compileJava", "playPrecompile").build()

        assertEquals(PREVIEW_MINOR_VERSION, minorVersion(File(app, "build/classes/java/main/controllers/Application.class")), "compileJava")
        assertEquals(PREVIEW_MINOR_VERSION, minorVersion(File(app, "precompiled/java/controllers/Application.class")), "Play's compile")
    }

    private fun minorVersion(classFile: File): Int =
        DataInputStream(classFile.inputStream()).use { it.readInt(); it.readUnsignedShort() }

    private companion object {
        const val PREVIEW_MINOR_VERSION = 65535
    }
}
