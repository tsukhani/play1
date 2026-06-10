package play.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers the PF-90 framework-bundled module path: extractPlayModules copies each
 * declared module's directory from <frameworkPath>/modules/<name>/ into the
 * app's modules/<name>/. (The Ivy-resolved zip path via the `playModule`
 * configuration needs a real dependency resolution and is out of TestKit scope —
 * see ExtractPlayModulesTask.extract(); the directory branch is the one consumers
 * exercise via play1 { modules("...") }.)
 */
class ExtractPlayModulesTest {

    /**
     * Build a minimal fake "framework distribution" that satisfies both
     * _playValidateFramework (needs framework/play-<version>.jar) and the
     * frameworkModules source (needs modules/<name>/ directories). Returns the
     * framework root dir.
     */
    private fun fakeFramework(root: File, version: String, vararg moduleNames: String): File {
        File(root, "framework").mkdirs()
        // _playValidateFramework checks for this exact jar name; contents are
        // irrelevant (the extract path never reads it).
        File(root, "framework/play-$version.jar").writeText("not-a-real-jar")
        moduleNames.forEach { name ->
            val moduleDir = File(root, "modules/$name").apply { mkdirs() }
            // Representative module layout: a conf/, an app/ controller, a
            // public/ asset, and a play.plugins descriptor.
            File(moduleDir, "conf").mkdirs()
            File(moduleDir, "conf/messages").writeText("hello=world\n")
            File(moduleDir, "app/controllers").mkdirs()
            File(moduleDir, "app/controllers/Marker.java").writeText("// $name marker\n")
            File(moduleDir, "commands.py").writeText("# $name\n")
        }
        return root
    }

    @Test
    fun `extractPlayModules materializes a framework-bundled module into the app modules dir`(@TempDir tmp: File) {
        val fwDir = File(tmp, "framework-dist")
        fakeFramework(fwDir, "9.9.9-TEST", "somemodule")

        val appDir = File(tmp, "app-project").apply { mkdirs() }
        TestProject.write(
            appDir,
            play1Block = """
                frameworkVersion.set("9.9.9-TEST")
                frameworkPath.set(file("${fwDir.absolutePath.replace("\\", "\\\\")}"))
                modules("somemodule")
            """.trimIndent()
        )

        val result = TestProject.runner(appDir, "extractPlayModules").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":extractPlayModules")?.outcome)
        // _playValidateFramework is a dependency; if the fake jar were missing it
        // would fail the build, so SUCCESS here also proves the validate gate ran.
        assertEquals(TaskOutcome.SUCCESS, result.task(":_playValidateFramework")?.outcome)

        // The module's tree should now exist under the app's modules/<name>/.
        val extracted = File(appDir, "modules/somemodule")
        assertTrue(extracted.isDirectory, "modules/somemodule/ should be materialized")
        assertTrue(
            File(extracted, "conf/messages").isFile,
            "conf/messages should be copied from the framework-bundled module"
        )
        assertTrue(
            File(extracted, "app/controllers/Marker.java").isFile,
            "app sources should be copied from the framework-bundled module"
        )
        assertEquals(
            "hello=world\n",
            File(extracted, "conf/messages").readText(),
            "copied file content should match the source"
        )
    }

    @Test
    fun `extractPlayModules with no declared modules produces an empty modules dir`(@TempDir tmp: File) {
        val fwDir = File(tmp, "framework-dist")
        fakeFramework(fwDir, "9.9.9-TEST", "unused")

        val appDir = File(tmp, "app-project").apply { mkdirs() }
        TestProject.write(
            appDir,
            play1Block = """
                frameworkVersion.set("9.9.9-TEST")
                frameworkPath.set(file("${fwDir.absolutePath.replace("\\", "\\\\")}"))
            """.trimIndent()
        )

        val result = TestProject.runner(appDir, "extractPlayModules").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":extractPlayModules")?.outcome)

        // Mutation sanity: a module the app did NOT declare must NOT be copied,
        // even though it exists in the framework distribution.
        assertTrue(
            !File(appDir, "modules/unused").exists(),
            "Modules not declared via play1 { modules(...) } must not be extracted"
        )
    }
}
