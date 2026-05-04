package play.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
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

        project.tasks.register<PlayAutotestTask>("playAutotest") {
            group = "play1"
            description = "Run all application tests headlessly via FirePhoque"
            dependsOn("extractPlayModules")

            frameworkPath.set(ext.frameworkPath)
            frameworkVersion.set(ext.frameworkVersion)
            httpPort.set(ext.httpPort)
            applicationPath.set(project.layout.projectDirectory)

            val frameworkJar = ext.frameworkPath.file(ext.frameworkVersion.map { "framework/play-$it.jar" })
            val frameworkLibDir = ext.frameworkPath.dir("framework/lib")
            playClasspath.from(
                project.layout.projectDirectory.dir("conf"),
                frameworkJar,
                project.configurations.named("playFramework"),
                project.fileTree("lib") { include("**/*.jar") },
                project.fileTree("modules") { include("*/lib/*.jar") },
                project.provider { project.fileTree(frameworkLibDir.get().asFile) { include("**/*.jar") } },
                project.provider {
                    project.fileTree(ext.frameworkPath.dir("modules/testrunner/lib").get().asFile) { include("**/*.jar") }
                }
            )

            runUnit.set(project.findProperty("runUnit")?.toString().toBoolean())
            runFunctional.set(project.findProperty("runFunctional")?.toString().toBoolean())
            runSelenium.set(project.findProperty("runSelenium")?.toString().toBoolean())
            project.findProperty("webclientTimeout")?.toString()?.let { webclientTimeout.set(it) }

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

    private fun loadDotEnv(envFile: File): Map<String, String> {
        if (!envFile.isFile) return emptyMap()
        val out = linkedMapOf<String, String>()
        envFile.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            out[key] = value
        }
        return out
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

            loadDotEnv(File(project.projectDir, "certs/.env")).forEach { (k, v) ->
                environment(k, v)
            }

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

abstract class PlayAutotestTask : DefaultTask() {
    @get:Internal abstract val frameworkPath: DirectoryProperty
    @get:Internal abstract val frameworkVersion: Property<String>
    @get:Internal abstract val httpPort: Property<Int>
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val playClasspath: ConfigurableFileCollection

    @get:Input @get:Optional abstract val runUnit: Property<Boolean>
    @get:Input @get:Optional abstract val runFunctional: Property<Boolean>
    @get:Input @get:Optional abstract val runSelenium: Property<Boolean>
    @get:Input @get:Optional abstract val webclientTimeout: Property<String>

    @get:Inject abstract val fileSystemOps: FileSystemOperations

    @TaskAction
    fun runAutotest() {
        val appDir = applicationPath.get().asFile
        val fwPath = frameworkPath.get().asFile
        val port = httpPort.get()
        val version = frameworkVersion.get()

        killExistingInstance(port, timeoutMs = 200)

        fileSystemOps.delete {
            delete(File(appDir, "tmp"), File(appDir, "test-result"))
        }

        val extraSysprops = buildList {
            if (runUnit.getOrElse(false)) add("-DrunUnitTests")
            if (runFunctional.getOrElse(false)) add("-DrunFunctionalTests")
            if (runSelenium.getOrElse(false)) add("-DrunSeleniumTests")
            webclientTimeout.orNull?.let { add("-DwebclientTimeout=$it") }
        }

        val logsDir = File(appDir, "logs").apply { mkdirs() }
        val systemOut = File(logsDir, "system.out").apply { writeText("") }
        val playJar = File(fwPath, "framework/play-$version.jar")

        val playCmd = buildList {
            add(javaExecutable())
            add("--enable-native-access=ALL-UNNAMED")
            add("-javaagent:${playJar.absolutePath}")
            add("-Dfile.encoding=utf-8")
            add("-Dapplication.path=${appDir.absolutePath}")
            add("-Dplay.id=test")
            add("-Dplay.version=$version")
            addAll(extraSysprops)
            add("-classpath")
            add(playClasspath.asPath)
            add("play.server.Server")
            add("--http.port=$port")
        }

        logger.lifecycle("~ Starting Play in test mode...")
        val playPb = ProcessBuilder(playCmd)
            .directory(appDir)
            .redirectOutput(systemOut)
            .redirectErrorStream(true)
        val dotenv = File(appDir, "certs/.env")
        if (dotenv.isFile) {
            dotenv.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                playPb.environment().putIfAbsent(key, value)
            }
        }
        val playProcess = playPb.start()

        try {
            waitForReady(systemOut, playProcess)
            logger.lifecycle("~ Server is up and running")
            logger.lifecycle("~ Starting FirePhoque...")

            val fpCp = buildList {
                add(File(fwPath, "modules/testrunner/conf").absolutePath)
                add(File(fwPath, "modules/testrunner/lib/play-testrunner.jar").absolutePath)
                File(fwPath, "modules/testrunner/firephoque").listFiles()
                    ?.filter { it.name.endsWith(".jar") }
                    ?.forEach { add(it.absolutePath) }
            }

            val fpCmd = buildList {
                add(javaExecutable())
                add("--enable-native-access=ALL-UNNAMED")
                addAll(extraSysprops)
                add("-Djava.util.logging.config.file=logging.properties")
                add("-classpath")
                add(fpCp.joinToString(File.pathSeparator))
                add("-Dapplication.url=http://localhost:$port")
                add("-DheadlessBrowser=")
                add("play.modules.testrunner.FirePhoque")
            }

            val fpExit = ProcessBuilder(fpCmd)
                .directory(appDir)
                .inheritIO()
                .start()
                .waitFor()

            val testResultDir = File(appDir, "test-result")
            val passed = File(testResultDir, "result.passed").exists()
            val failed = File(testResultDir, "result.failed").exists()

            if (passed) logger.lifecycle("~ All tests passed")
            if (failed) logger.lifecycle("~ Some tests failed. See ${testResultDir.absolutePath} for results")

            killExistingInstance(port, timeoutMs = 500)

            when {
                failed -> throw GradleException("Tests failed (FirePhoque exit=$fpExit)")
                !passed -> throw GradleException("Tests did not successfully complete (FirePhoque exit=$fpExit)")
            }
        } finally {
            if (playProcess.isAlive) {
                playProcess.destroy()
                if (!playProcess.waitFor(5, TimeUnit.SECONDS)) {
                    playProcess.destroyForcibly()
                }
            }
        }
    }

    private fun killExistingInstance(port: Int, timeoutMs: Int) {
        try {
            val conn = URI("http://localhost:$port/@kill").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 100
            conn.readTimeout = timeoutMs
            try { conn.inputStream.close() } catch (_: Exception) {}
        } catch (_: Exception) {
            // No existing server, or it killed itself before responding — both fine
        }
    }

    private fun waitForReady(systemOut: File, process: Process, timeoutSeconds: Int = 60) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw GradleException("Play process died before becoming ready. See ${systemOut.absolutePath}")
            }
            if (systemOut.exists() && systemOut.readText().contains("Server is up and running")) {
                return
            }
            Thread.sleep(200)
        }
        throw GradleException("Play did not become ready within ${timeoutSeconds}s")
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        return "$javaHome/bin/java"
    }
}
