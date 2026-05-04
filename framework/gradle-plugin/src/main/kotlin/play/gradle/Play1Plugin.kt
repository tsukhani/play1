package play.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

class Play1Plugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.configurations.create("playFramework").apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "The Play 1 framework jar"
        }

        val playModule = project.configurations.create("playModule").apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Play 1 modules (zip artifacts)"
        }

        project.tasks.register<ExtractPlayModulesTask>("extractPlayModules") {
            group = "play1"
            description = "Extract Play module zips into modules/<name>/"
            moduleZips.from(playModule)
            outputDir.set(project.layout.projectDirectory.dir("modules"))
        }
    }
}

abstract class ExtractPlayModulesTask : DefaultTask() {
    @get:InputFiles
    abstract val moduleZips: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archiveOps: ArchiveOperations

    @get:Inject
    abstract val fileSystemOps: FileSystemOperations

    @TaskAction
    fun extract() {
        val outDir = outputDir.get().asFile
        moduleZips.forEach { zip ->
            val baseName = zip.nameWithoutExtension
            val moduleName = if (baseName.contains("-"))
                baseName.substringBeforeLast('-')
            else baseName
            val dest = outDir.resolve(moduleName)
            fileSystemOps.delete {
                delete(dest)
            }
            fileSystemOps.copy {
                from(archiveOps.zipTree(zip))
                into(dest)
            }
        }
    }
}
