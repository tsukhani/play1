package play.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

        registerPlayJvmTask(project, ext, "playRun",
            description = "Run the Play application in development mode",
            playIdOverride = null,
            extraSysprops = emptyList(),
            includeHttpPort = true)

        registerPlayJvmTask(project, ext, "playTest",
            description = "Run the Play application in test mode (auto-mounts the testrunner module)",
            playIdOverride = "test",
            extraSysprops = emptyList(),
            includeHttpPort = true)

        project.tasks.register<Delete>("playPrecompileClean") {
            delete(
                project.layout.projectDirectory.dir("tmp"),
                project.layout.projectDirectory.dir("precompiled")
            )
        }

        registerPlayJvmTask(project, ext, "playPrecompile",
            description = "Precompile all Java sources and templates into precompiled/",
            playIdOverride = "test",
            extraSysprops = listOf("-Dprecompile=yes"),
            includeHttpPort = false,
            extraDependsOn = listOf("playPrecompileClean"))

        project.tasks.register<PlayDistTask>("playDist") {
            group = "play1"
            description = "Package the application as a ZIP distribution"
            projectDir.set(project.layout.projectDirectory)
            outputFile.set(project.layout.projectDirectory.dir("dist").file("${project.name}.zip"))
            outputs.upToDateWhen { false }
        }
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

    private fun registerPlayJvmTask(
        project: Project,
        ext: Play1Extension,
        taskName: String,
        description: String,
        playIdOverride: String?,
        extraSysprops: List<String>,
        includeHttpPort: Boolean,
        extraDependsOn: List<String> = emptyList(),
    ) {
        val isTestMode = playIdOverride?.startsWith("test") == true

        project.tasks.register<JavaExec>(taskName) {
            group = "play1"
            this.description = description
            dependsOn("extractPlayModules")
            extraDependsOn.forEach { dependsOn(it) }

            mainClass.set("play.server.Server")

            val frameworkJar = ext.frameworkPath.file(ext.frameworkVersion.map { "framework/play-$it.jar" })
            val frameworkLibDir = ext.frameworkPath.dir("framework/lib")
            val testrunnerLib = project.provider {
                if (isTestMode) {
                    project.fileTree(ext.frameworkPath.dir("modules/testrunner/lib").get().asFile) { include("**/*.jar") }
                } else {
                    project.files()
                }
            }

            classpath = project.files(
                project.layout.projectDirectory.dir("conf"),
                frameworkJar,
                project.configurations.named("playFramework"),
                project.fileTree("lib") { include("**/*.jar") },
                project.fileTree("modules") { include("*/lib/*.jar") },
                project.provider { project.fileTree(frameworkLibDir.get().asFile) { include("**/*.jar") } },
                testrunnerLib
            )

            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=utf-8",
                "-Dapplication.path=${project.projectDir.absolutePath}"
            )
            val effectivePlayId = playIdOverride ?: ext.playId.get()
            jvmArgs("-Dplay.id=$effectivePlayId")
            jvmArgs(ext.frameworkVersion.map { "-Dplay.version=$it" }.get())
            jvmArgs("-javaagent:${frameworkJar.get().asFile.absolutePath}")
            extraSysprops.forEach { jvmArgs(it) }

            if (includeHttpPort) {
                args(ext.httpPort.map { "--http.port=$it" }.get())
            }

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

abstract class PlayDistTask : DefaultTask() {
    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun dist() {
        val projDir = projectDir.get().asFile
        val outFile = outputFile.get().asFile
        val appName = projDir.name

        val gitOutput = ByteArrayOutputStream()
        execOps.exec {
            commandLine("git", "ls-files", "--cached", "--others", "--exclude-standard")
            workingDir = projDir
            standardOutput = gitOutput
        }
        val gitFiles = gitOutput.toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

        val distignore = projDir.resolve(".distignore")
        val ignorePrefixes = if (distignore.isFile) {
            distignore.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else emptyList()

        val outRel = outFile.parentFile.relativeTo(projDir).path
        val outRelPrefix = if (outRel.isEmpty()) null else "$outRel/"

        outFile.parentFile.mkdirs()
        if (outFile.exists()) outFile.delete()

        ZipOutputStream(outFile.outputStream()).use { zip ->
            for (relpath in gitFiles.sorted()) {
                if (outRelPrefix != null && relpath.startsWith(outRelPrefix)) continue
                if (ignorePrefixes.any { relpath.startsWith(it) }) continue
                val srcFile = projDir.resolve(relpath)
                if (!srcFile.isFile) continue
                zip.putNextEntry(ZipEntry("$appName/$relpath"))
                srcFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        logger.lifecycle("Distribution created at ${outFile.absolutePath}")
    }
}
