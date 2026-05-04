package play.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

abstract class Play1Extension {
    abstract val frameworkPath: DirectoryProperty
    abstract val frameworkVersion: Property<String>
    abstract val playId: Property<String>
    abstract val httpPort: Property<Int>
}

class Play1Plugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("java")

        val ext = project.extensions.create<Play1Extension>("play1").apply {
            frameworkVersion.convention("1.13.0-SNAPSHOT")
            playId.convention("")
            httpPort.convention(9000)
        }

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

        configureSourceSets(project, ext)

        project.tasks.register<ExtractPlayModulesTask>("extractPlayModules") {
            group = "play1"
            description = "Extract Play module zips into modules/<name>/"
            moduleZips.from(playModule)
            outputDir.set(project.layout.projectDirectory.dir("modules"))
        }

        registerPlayRun(project, ext)
    }

    private fun configureSourceSets(project: Project, ext: Play1Extension) {
        val javaExt = project.extensions.getByType<JavaPluginExtension>()
        val frameworkClasspath = project.files(
            ext.frameworkPath.file(ext.frameworkVersion.map { "framework/play-$it.jar" }),
            project.provider {
                project.fileTree(ext.frameworkPath.dir("framework/lib").get().asFile) { include("**/*.jar") }
            },
            project.fileTree("lib") { include("**/*.jar") },
            project.fileTree("modules") { include("*/lib/*.jar") }
        )
        javaExt.sourceSets.named("main").configure {
            java.setSrcDirs(listOf("app"))
            resources.setSrcDirs(listOf("conf"))
            compileClasspath += frameworkClasspath
        }
        javaExt.sourceSets.named("test").configure {
            java.setSrcDirs(listOf("test"))
            compileClasspath += frameworkClasspath
        }
    }

    private fun registerPlayRun(project: Project, ext: Play1Extension) {
        project.tasks.register<JavaExec>("playRun") {
            group = "play1"
            description = "Run the Play application in development mode"
            dependsOn("extractPlayModules")

            mainClass.set("play.server.Server")

            val frameworkJar = ext.frameworkPath.file(ext.frameworkVersion.map { "framework/play-$it.jar" })
            val frameworkLibDir = ext.frameworkPath.dir("framework/lib")

            classpath = project.files(
                project.layout.projectDirectory.dir("conf"),
                frameworkJar,
                project.configurations.named("playFramework"),
                project.fileTree("lib") { include("**/*.jar") },
                project.fileTree("modules") { include("*/lib/*.jar") },
                project.provider { project.fileTree(frameworkLibDir.get().asFile) { include("**/*.jar") } }
            )

            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=utf-8",
                "-Dapplication.path=${project.projectDir.absolutePath}"
            )
            jvmArgs(ext.playId.map { "-Dplay.id=$it" }.get())
            jvmArgs(ext.frameworkVersion.map { "-Dplay.version=$it" }.get())
            jvmArgs("-javaagent:${frameworkJar.get().asFile.absolutePath}")

            args(ext.httpPort.map { "--http.port=$it" }.get())

            standardInput = System.`in`
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
