package play.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.provider.ListProperty
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
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

abstract class Play1Extension {
    abstract val frameworkPath: DirectoryProperty
    abstract val frameworkVersion: Property<String>
    abstract val modules: ListProperty<String>

    fun modules(vararg names: String) {
        modules.addAll(*names)
    }
}

class Play1Plugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("java")

        val ext = project.extensions.create<Play1Extension>("play1").apply {
            frameworkVersion.convention("1.13.0-SNAPSHOT")
            modules.convention(emptyList())
        }
        // play.id and http.port are NOT exposed as DSL properties. Configure them in
        // conf/application.conf (or %prefix.* overrides). Override at task time via
        // -PplayId=staging or -PhttpPort=8080. The plugin reads these gradle
        // properties directly inside each task that needs them.

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

        // compileJava/compileTestJava read modules/*/lib/*.jar via the source set
        // compileClasspath we configured above. They need extractPlayModules to have
        // populated modules/ before they run, otherwise Gradle 9+ flags an
        // implicit-dependency error.
        project.tasks.matching { it.name == "compileJava" || it.name == "compileTestJava" }
            .configureEach { dependsOn("extractPlayModules") }

        // Fail loudly when the configured frameworkVersion does not resolve to a
        // real jar in $frameworkPath/framework/. Without this check, project.files()
        // silently drops missing paths from the compile classpath, producing an
        // unhelpful wall of "package play does not exist" errors at javac time.
        // Wired in as a dependency of extractPlayModules below, so every
        // framework-consuming task (compileJava, playRun, playTest, playPrecompile,
        // playBundle, ...) inherits it transitively.
        project.tasks.register("_playValidateFramework") {
            val jarProvider = ext.frameworkPath.file(ext.frameworkVersion.map { "framework/play-$it.jar" })
            val versionProvider = ext.frameworkVersion
            val frameworkDirProvider = ext.frameworkPath.dir("framework")
            doLast {
                val jar = jarProvider.get().asFile
                if (jar.exists()) return@doLast
                val frameworkDir = frameworkDirProvider.get().asFile
                val available = frameworkDir.listFiles { _, name -> name.matches(Regex("play-.*\\.jar")) }
                    ?.map { it.name }?.sorted() ?: emptyList()
                throw GradleException(buildString {
                    appendLine("Play framework jar not found: ${jar.absolutePath}")
                    appendLine("  Configured frameworkVersion = '${versionProvider.get()}'")
                    if (available.isEmpty()) {
                        appendLine("  No play-*.jar files exist in $frameworkDir.")
                        appendLine("  Check that play1 { frameworkPath } points to a built Play 1 distribution.")
                    } else {
                        appendLine("  Available jars: ${available.joinToString(", ")}")
                        appendLine("  Set play1 { frameworkVersion } in build.gradle.kts to match an available jar.")
                    }
                })
            }
        }

        project.tasks.register<ExtractPlayModulesTask>("extractPlayModules") {
            group = "play1"
            description = "Populate modules/<name>/ from framework-bundled and Ivy-resolved sources"
            dependsOn("_playValidateFramework")
            moduleZips.from(playModule)
            frameworkModules.from(project.provider {
                val modulesRoot = ext.frameworkPath.dir("modules").get().asFile
                ext.modules.get().mapNotNull { name ->
                    val candidate = File(modulesRoot, name)
                    if (candidate.isDirectory) candidate else null
                }
            })
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
            description = "Package the application source + precompiled classes + frontend SPA as <rootProject.name>.zip (respects .gitignore + .distignore). Optional: -Poutput=<path>"
            dependsOn("playPrecompile")
            projectDir.set(project.layout.projectDirectory)
            projectName.set(project.name)
            val customOutput = project.providers.gradleProperty("output").orNull
            outputFile.set(
                if (customOutput != null && customOutput.isNotBlank()) {
                    project.layout.projectDirectory.file(customOutput)
                } else {
                    project.layout.projectDirectory.dir("dist").file("${project.name}.zip")
                }
            )
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayBundleTask>("playBundle") {
            group = "play1"
            description = "Self-contained <rootProject.name>-bundle.zip (source + precompiled + frontend SPA + framework + deps + bundled `play` launcher). Java 25+ is the only runtime dependency. Optional: -Poutput=<path>"
            dependsOn("extractPlayModules", "playPrecompile")
            projectDir.set(project.layout.projectDirectory)
            projectName.set(project.name)
            frameworkPath.set(ext.frameworkPath)
            frameworkVersion.set(ext.frameworkVersion)
            playClasspath.from(playClasspathFor(project, ext, includeTestrunner = false))
            val customOutput = project.providers.gradleProperty("output").orNull
            outputFile.set(
                if (customOutput != null && customOutput.isNotBlank()) {
                    project.layout.projectDirectory.file(customOutput)
                } else {
                    project.layout.projectDirectory.dir("dist").file("${project.name}-bundle.zip")
                }
            )
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayAutotestTask>("playAutotest") {
            group = "play1"
            description = "Run all application tests headlessly via FirePhoque"
            dependsOn("extractPlayModules")

            frameworkPath.set(ext.frameworkPath)
            frameworkVersion.set(ext.frameworkVersion)
            httpPort.set(project.providers.gradleProperty("httpPort").map { it.toInt() })
            applicationPath.set(project.layout.projectDirectory)
            playClasspath.from(playClasspathFor(project, ext, includeTestrunner = true))

            runUnit.set(project.findProperty("runUnit")?.toString().toBoolean())
            runFunctional.set(project.findProperty("runFunctional")?.toString().toBoolean())
            project.findProperty("webclientTimeout")?.toString()?.let { webclientTimeout.set(it) }

            outputs.upToDateWhen { false }
        }

        project.tasks.register<Delete>("playClean") {
            group = "play1"
            description = "Delete the tmp/ directory"
            delete(project.layout.projectDirectory.dir("tmp"))
        }

        registerPlayJvmTask(project, ext, "playEvolutions",
            description = "Run Play DB Evolutions. Optional: -Pmode=apply|resolve|markApplied",
            playIdOverride = null,
            extraSysprops = project.providers.gradleProperty("mode").orNull
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf("-Dmode=$it") }
                ?: emptyList(),
            includeHttpPort = false,
            mainClassName = "play.db.Evolutions")

        project.tasks.register<PlaySecretTask>("playSecret") {
            group = "play1"
            description = "Generate a new application secret and write to certs/.env"
            applicationPath.set(project.layout.projectDirectory)
            outputs.upToDateWhen { false }
        }

        project.tasks.register("playVersion") {
            group = "play1"
            description = "Print the Play framework version"
            val ver = ext.frameworkVersion
            doLast {
                println(ver.get())
            }
        }

        project.tasks.register("playClasspath") {
            group = "play1"
            description = "Print the computed classpath"
            val cp = playClasspathFor(project, ext, includeTestrunner = false)
            doLast {
                cp.files.sortedBy { it.absolutePath }.forEach { println(it.absolutePath) }
            }
        }

        project.tasks.register<PlayStartTask>("playStart") {
            group = "play1"
            description = "Start the application in the background. Optional: -Ppid-file=<name>"
            dependsOn("extractPlayModules")
            applicationPath.set(project.layout.projectDirectory)
            frameworkPath.set(ext.frameworkPath)
            frameworkVersion.set(ext.frameworkVersion)
            // -PplayId is the only override channel; absent means "" (no %prefix override).
            // -PhttpPort absent means "let conf decide" (Property stays not-present).
            playId.set(project.providers.gradleProperty("playId").orElse(""))
            httpPort.set(project.providers.gradleProperty("httpPort").map { it.toInt() })
            pidFileOverride.set(project.providers.gradleProperty("pid-file").orElse(""))
            // -PjvmArgs="..." carries 1.12-style JVM tuning forwarded by the
            // play wrapper from the user's command line.
            extraJvmArgs.set(project.providers.gradleProperty("jvmArgs").orElse(""))
            playClasspath.from(playClasspathFor(project, ext, includeTestrunner = false))
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayStopTask>("playStop") {
            group = "play1"
            description = "Stop the running application. Optional: -Ppid-file=<name>"
            applicationPath.set(project.layout.projectDirectory)
            pidFileOverride.set(project.providers.gradleProperty("pid-file").orElse(""))
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayRestartTask>("playRestart") {
            group = "play1"
            description = "Restart the running application. Optional: -Ppid-file=<name>"
            dependsOn("extractPlayModules")
            applicationPath.set(project.layout.projectDirectory)
            frameworkPath.set(ext.frameworkPath)
            frameworkVersion.set(ext.frameworkVersion)
            playId.set(project.providers.gradleProperty("playId").orElse(""))
            httpPort.set(project.providers.gradleProperty("httpPort").map { it.toInt() })
            pidFileOverride.set(project.providers.gradleProperty("pid-file").orElse(""))
            extraJvmArgs.set(project.providers.gradleProperty("jvmArgs").orElse(""))
            playClasspath.from(playClasspathFor(project, ext, includeTestrunner = false))
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayPidTask>("playPid") {
            group = "play1"
            description = "Show the PID of the running application. Optional: -Ppid-file=<name>"
            applicationPath.set(project.layout.projectDirectory)
            pidFileOverride.set(project.providers.gradleProperty("pid-file").orElse(""))
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayOutTask>("playOut") {
            group = "play1"
            description = "Tail the logs/system.out file. Optional: -Ppid-file=<name>"
            applicationPath.set(project.layout.projectDirectory)
            pidFileOverride.set(project.providers.gradleProperty("pid-file").orElse(""))
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayModulesInfoTask>("playModulesInfo") {
            group = "play1"
            description = "List the modules that would be loaded for this app"
            applicationPath.set(project.layout.projectDirectory)
            frameworkPath.set(ext.frameworkPath)
            playId.set(project.providers.gradleProperty("playId").orElse(""))
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayJavadocTask>("playJavadoc") {
            group = "play1"
            description = "Generate Javadoc for the application. Optional: -Pinclude-modules (include declared module sources), -Plinks (add external API doc links)"
            dependsOn("extractPlayModules")
            applicationPath.set(project.layout.projectDirectory)
            frameworkPath.set(ext.frameworkPath)
            frameworkVersion.set(ext.frameworkVersion)
            modules.set(ext.modules)
            playClasspath.from(playClasspathFor(project, ext, includeTestrunner = false))
            withLinks.set(project.providers.gradleProperty("links").map { true }.orElse(false))
            includeModules.set(project.providers.gradleProperty("include-modules").map { true }.orElse(false))
            outputs.upToDateWhen { false }
        }

        project.tasks.register<PlayStatusTask>("playStatus") {
            group = "play1"
            description = "Report whether the application is running and dump /@status JSON if reachable. Optional: -Ppid-file=<name>, -PhttpPort=<port>"
            applicationPath.set(project.layout.projectDirectory)
            pidFileOverride.set(project.providers.gradleProperty("pid-file").orElse(""))
            httpPort.set(project.providers.gradleProperty("httpPort").map { it.toInt() })
            outputs.upToDateWhen { false }
        }
    }

    private fun configureSourceSets(project: Project, ext: Play1Extension) {
        val javaExt = project.extensions.getByType<JavaPluginExtension>()
        // Pin a Java 25 toolchain. The framework jar is built with Java 25
        // (class file 69), so apps must compile with a JDK that can read
        // class 69 bytecode (-> JDK 25+). Without this, environments that
        // resolve `java` to an older JDK (jenv shims, asdf, system Java)
        // produce "class file has wrong version 69.0, should be 65.0"
        // while reading play.mvc.Controller. Gradle auto-detects installed
        // JDKs (~/.gradle/jdks, /Library/Java/JavaVirtualMachines, etc.);
        // if no Java 25 is available, set
        // org.gradle.java.installations.auto-download=true in
        // ~/.gradle/gradle.properties to let Gradle fetch one.
        javaExt.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
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


    private fun playClasspathFor(project: Project, ext: Play1Extension, includeTestrunner: Boolean): FileCollection {
        val frameworkJar = ext.frameworkPath.file(ext.frameworkVersion.map { "framework/play-$it.jar" })
        val frameworkLibDir = ext.frameworkPath.dir("framework/lib")
        val testrunnerLib = project.provider {
            if (includeTestrunner) {
                project.fileTree(ext.frameworkPath.dir("modules/testrunner/lib").get().asFile) { include("**/*.jar") }
            } else {
                project.files()
            }
        }
        // Gradle-resolved Maven dependencies (implementation, etc.) live in main's
        // runtimeClasspath. Include them so consumers get their declared deps.
        val gradleRuntimeClasspath = project.provider {
            val javaExt = project.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
            javaExt?.sourceSets?.findByName("main")?.runtimeClasspath ?: project.files()
        }
        return project.files(
            project.layout.projectDirectory.dir("conf"),
            frameworkJar,
            project.configurations.named("playFramework"),
            gradleRuntimeClasspath,
            project.fileTree("lib") { include("**/*.jar") },
            project.fileTree("modules") { include("*/lib/*.jar") },
            project.provider { project.fileTree(frameworkLibDir.get().asFile) { include("**/*.jar") } },
            testrunnerLib
        )
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
        mainClassName: String = "play.server.Server",
    ) {
        val isTestMode = playIdOverride?.startsWith("test") == true

        project.tasks.register<JavaExec>(taskName) {
            group = "play1"
            this.description = description
            dependsOn("extractPlayModules")
            extraDependsOn.forEach { dependsOn(it) }

            mainClass.set(mainClassName)

            val frameworkJar = ext.frameworkPath.file(ext.frameworkVersion.map { "framework/play-$it.jar" })
            classpath = playClasspathFor(project, ext, isTestMode)

            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=utf-8",
                "-Dapplication.path=${project.projectDir.absolutePath}"
            )
            // Built-in tasks (playTest, playPrecompile, playAutotest) hardcode
            // playIdOverride = "test". For playRun and other "no override" tasks,
            // honor -PplayId from the command line; else default to empty (no
            // %prefix override applied).
            val effectivePlayId = playIdOverride
                ?: project.providers.gradleProperty("playId").orNull
                ?: ""
            jvmArgs("-Dplay.id=$effectivePlayId")
            jvmArgs(ext.frameworkVersion.map { "-Dplay.version=$it" }.get())
            jvmArgs("-javaagent:${frameworkJar.get().asFile.absolutePath}")
            confJvmArgs(project.projectDir, effectivePlayId).forEach { jvmArgs(it) }
            extraSysprops.forEach { jvmArgs(it) }

            // -PjvmArgs="..." carries 1.12-style JVM tuning flags that the
            // `play` wrapper accumulated from the user's command line
            // (-Xms, -Xmx, -XX:*, -D*, -javaagent:*, -agentlib:*).
            // Split on whitespace and forward each as a separate jvmArg so
            // the JVM sees them as discrete argv slots, not one giant string.
            project.providers.gradleProperty("jvmArgs").orNull?.let { extra ->
                extra.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { jvmArgs(it) }
            }

            val dotenv = loadDotEnv(File(project.projectDir, "certs/.env"))
            dotenv.forEach { (k, v) ->
                environment(k, v)
            }
            // Hermetic test runs (playTest, playPrecompile, playAutotest) — if
            // certs/.env and the host env both lack the application.secret env
            // var, synthesize an ephemeral one so `play autotest` works on a
            // fresh checkout without first running `play secret`. The framework
            // rejects literal secrets and demands a `${VAR}` placeholder.
            if (isTestMode) {
                ensureTestSecret(project.projectDir, dotenv)?.let { (varName, secret) ->
                    environment(varName, secret)
                    logger.lifecycle("~ Generated ephemeral $varName for this test run")
                }
            }

            // Only pass --http.port when -PhttpPort was supplied; otherwise let
            // conf/application.conf decide.
            if (includeHttpPort) {
                project.providers.gradleProperty("httpPort").orNull?.let {
                    args("--http.port=$it")
                }
            }

            standardInput = System.`in`
        }
    }
}

abstract class ExtractPlayModulesTask : DefaultTask() {
    @get:InputFiles
    abstract val moduleZips: ConfigurableFileCollection

    @get:InputFiles
    abstract val frameworkModules: ConfigurableFileCollection

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
            extractTo(outDir.resolve(moduleName)) {
                from(archiveOps.zipTree(zip))
            }
        }
        frameworkModules.forEach { srcDir ->
            extractTo(outDir.resolve(srcDir.name)) {
                from(srcDir)
            }
        }
    }

    private fun extractTo(dest: File, configure: org.gradle.api.file.CopySpec.() -> Unit) {
        fileSystemOps.delete {
            delete(dest)
        }
        fileSystemOps.copy {
            configure()
            into(dest)
        }
    }
}

abstract class PlayDistTask : DefaultTask() {
    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:Internal
    abstract val projectName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun dist() {
        val projDir = projectDir.get().asFile
        val outFile = outputFile.get().asFile
        // rootProject.name from settings.gradle.kts — used for both the zip
        // filename (set at registration) and the inner directory prefix, so a
        // project whose directory differs from rootProject.name still gets a
        // consistently-named archive.
        val appName = projectName.get()

        // Build the SPA if a Nuxt frontend is present at frontend/. Shared
        // helper between playDist and playBundle.
        buildFrontendAndCopySpa(projDir, execOps, logger)

        // Source file list from git: tracked + untracked-not-gitignored.
        // Honors all .gitignore files, including frontend/.gitignore (which
        // keeps node_modules/ and .output/ out of the zip).
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

        // Force-include the build outputs this task just produced. Typical
        // application .gitignore files exclude precompiled/ and public/spa/
        // (they're regenerated build artifacts), so git ls-files won't list
        // them — but excluding them from the dist defeats the purpose of
        // running playPrecompile + nuxi generate. .distignore (applied below)
        // still gets the final say, so users can exclude specific files
        // within these trees if needed.
        val forcedRoots = listOf("precompiled", "public/spa")
            .map { File(projDir, it) }
            .filter { it.isDirectory }
        val forcedFiles = forcedRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(projDir).path.replace(File.separatorChar, '/') }
                .toList()
        }
        val allFiles = (gitFiles + forcedFiles).distinct().sorted()

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

        // Use the JDK's ZipFileSystem provider with enablePosixFileAttributes
        // so the source file's executable bit (0755 vs 0644) is preserved in
        // the zip entry's Unix mode field. Plain ZipOutputStream has no public
        // setter for external attributes, so shell scripts and other +x files
        // would lose their executable bit on extract — `unzip` falls back to
        // the umask and shell scripts come out as 0644.
        val zipPath = outFile.toPath()
        val env = mapOf(
            "create" to "true",
            "enablePosixFileAttributes" to "true",
        )
        FileSystems.newFileSystem(zipPath, env).use { zipfs ->
            for (relpath in allFiles) {
                if (outRelPrefix != null && relpath.startsWith(outRelPrefix)) continue
                if (ignorePrefixes.any { relpath.startsWith(it) }) continue
                val srcFile = projDir.resolve(relpath)
                if (!srcFile.isFile) continue
                copyToZipfs(srcFile.toPath(), zipfs.getPath("/$appName/$relpath"))
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
    @get:Input @get:Optional abstract val webclientTimeout: Property<String>

    @get:Inject abstract val fileSystemOps: FileSystemOperations

    @TaskAction
    fun runAutotest() {
        val appDir = applicationPath.get().asFile
        val fwPath = frameworkPath.get().asFile

        // Parity with the legacy `app.check()` — surface a clear error before we
        // start spawning JVMs. Without this, missing conf/routes manifests as a
        // cryptic NPE deep in Play.bootstrap.
        val routesFile = File(appDir, "conf/routes")
        val confFile = File(appDir, "conf/application.conf")
        if (!routesFile.isFile || !confFile.isFile) {
            throw GradleException(
                "~ Oops. conf/routes or conf/application.conf missing.\n" +
                "~ ${appDir.absolutePath} does not seem to host a valid application."
            )
        }

        val confText = confFile.readText()
        // Resolve conf values with %test prefix priority — playAutotest always
        // runs with play.id=test, so %test.<key> overrides <key>. Without this,
        // FirePhoque would connect to the top-level http.port while the
        // framework actually bound to %test.http.port (or vice versa).
        val port = httpPort.orNull
            ?: confValue(confText, "http.port", "test")?.toIntOrNull()
            ?: 9000
        val headlessBrowser = confValue(confText, "headlessBrowser", "test").orEmpty()
        val effectiveTimeout = webclientTimeout.orNull
            ?: confValue(confText, "webclient.timeout", "test")
        val version = frameworkVersion.get()

        killExistingInstance(port, timeoutMs = 200)

        fileSystemOps.delete {
            delete(File(appDir, "tmp"), File(appDir, "test-result"))
        }

        val extraSysprops = buildList {
            if (runUnit.getOrElse(false)) add("-DrunUnitTests")
            if (runFunctional.getOrElse(false)) add("-DrunFunctionalTests")
            effectiveTimeout?.let { add("-DwebclientTimeout=$it") }
        }

        val logsDir = File(appDir, "logs").apply { mkdirs() }
        val systemOut = File(logsDir, "system.out").apply { writeText("") }
        val playJar = File(fwPath, "framework/play-$version.jar")

        val playCmd = buildList {
            add(javaExecutable())
            add("--enable-native-access=ALL-UNNAMED")
            add("-javaagent:${playJar.absolutePath}")
            addAll(confJvmArgs(appDir, "test"))
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
        val dotenv = loadDotEnv(File(appDir, "certs/.env"))
        dotenv.forEach { (k, v) ->
            playPb.environment().putIfAbsent(k, v)
        }
        ensureTestSecret(appDir, dotenv)?.let { (varName, secret) ->
            playPb.environment().putIfAbsent(varName, secret)
            logger.lifecycle("~ Generated ephemeral $varName for this test run")
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
                add("-DheadlessBrowser=$headlessBrowser")
                add("play.modules.testrunner.FirePhoque")
            }

            // Capture FirePhoque's stdout/stderr and forward each line through
            // logger.lifecycle so per-test result lines actually reach the user.
            // inheritIO() does NOT work here: under the Gradle daemon, fd 1 is a
            // protocol pipe to the Gradle client, not a terminal — bytes written
            // to it from a forked subprocess are silently dropped.
            val fpProcess = ProcessBuilder(fpCmd)
                .directory(appDir)
                .redirectErrorStream(true)
                .start()
            fpProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.lifecycle(it) }
            }
            val fpExit = fpProcess.waitFor()

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

abstract class PlaySecretTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty

    @TaskAction
    fun rotate() {
        val appDir = applicationPath.get().asFile
        val varName = readSecretVarName(File(appDir, "conf/application.conf"))
        val secret = generateSecret()
        val envFile = File(appDir, "certs/.env").apply { parentFile.mkdirs() }
        writeEnvVar(envFile, varName, secret)
        try {
            java.nio.file.Files.setPosixFilePermissions(envFile.toPath(),
                java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ))
        } catch (_: UnsupportedOperationException) { /* non-POSIX FS */ }
        ensureEnvExample(File(appDir, "certs/.env.example"), varName)
        logger.lifecycle("~ $varName written to ${envFile.absolutePath}")
        logger.lifecycle("~ Keep this value secret and consistent across all instances of your app.")
    }

    private fun writeEnvVar(envFile: File, varName: String, value: String) {
        val newLine = "$varName=$value"
        if (!envFile.exists()) {
            envFile.writeText(newLine + "\n")
            return
        }
        val lines = envFile.readLines().toMutableList()
        var replaced = false
        for (i in lines.indices) {
            val stripped = lines[i].trimStart()
            if (stripped.startsWith("$varName=") || stripped.startsWith("$varName ")) {
                lines[i] = newLine
                replaced = true
                break
            }
        }
        if (!replaced) lines.add(newLine)
        envFile.writeText(lines.joinToString("\n") + "\n")
    }

    private fun ensureEnvExample(exampleFile: File, varName: String) {
        if (exampleFile.exists()) return
        exampleFile.parentFile.mkdirs()
        exampleFile.writeText(buildString {
            appendLine("# Environment variables for this Play application.")
            appendLine("#")
            appendLine("# Copy this file to `certs/.env` (which is gitignored) and fill in real values:")
            appendLine("#     cp certs/.env.example certs/.env")
            appendLine()
            appendLine("$varName=")
        })
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

private fun activeValue(config: String, key: String): String? {
    val m = Regex("""^${Regex.escape(key)}\s*=\s*(.+?)\s*$""", RegexOption.MULTILINE).find(config)
    return m?.groupValues?.get(1)?.trim()
}

// %playId-aware lookup. Mirrors how Play 1's ConfigurationParser resolves
// `%test.<key>` to override `<key>` when play.id=test. Plain activeValue() is
// not prefix-aware, which is correct for the cert/HTTPS plumbing — but tasks
// that run with a hardcoded playId (playAutotest, playTest) must honor the
// %prefix or they'll disagree with the framework about which port/setting is
// active.
private fun confValue(config: String, key: String, playId: String): String? {
    if (playId.isNotEmpty()) {
        activeValue(config, "%$playId.$key")?.let { return it }
    }
    return activeValue(config, key)
}

// PF-92: lift conf entries that are JVM-level flags (not in-process config)
// onto the spawned JVM's command line, mirroring the 1.12 Python launcher's
// java_cmd. Without this, conf-declared agents/memory/JMX are silently
// dropped under 1.13.x — e.g. `%test.javaagent.path=bin/jacocoagent.jar`
// fails to attach JaCoCo, producing no coverage data.
//
// Resolution happens here rather than in Play.bootstrap because all four
// shapes are JVM startup flags: by the time the framework parses
// application.conf in-process, the JVM is already up and any agent /
// memory / JMX wiring is locked in.
//
// %<playId>. prefix priority matches Play's runtime config resolution.
// Returns an empty list when conf is missing or no relevant keys are set.
//
// Keys handled (parity with 1.12):
//   - javaagent.path   -> -javaagent:<path>
//   - agentlib         -> -agentlib:<spec>
//   - jvm.memory       -> whitespace-split into discrete JVM args
//   - jmx.port + jmx.hostname (both required) -> JMX agent flags
//
// JMX defaults (ssl=false, authenticate=false, local.only=false) match the
// 1.12 launcher verbatim. They are insecure-by-default but only fire when
// an operator explicitly sets both jmx.port and jmx.hostname; harden the
// agent with -D overrides via -PjvmArgs if exposing JMX off-host.
private fun confJvmArgs(appDir: File, playId: String): List<String> {
    val confFile = File(appDir, "conf/application.conf")
    if (!confFile.isFile) return emptyList()
    val confText = confFile.readText()
    return buildList {
        confValue(confText, "javaagent.path", playId)?.takeIf { it.isNotBlank() }?.let {
            add("-javaagent:$it")
        }
        confValue(confText, "agentlib", playId)?.takeIf { it.isNotBlank() }?.let {
            add("-agentlib:$it")
        }
        // jvm.memory is a whitespace-separated bag of JVM flags
        // (e.g. "-Xms256M -Xmx2G"); split and add each as a discrete arg.
        // JVM `-Xm*` semantics are last-wins, so a user's -PjvmArgs="-Xmx4G"
        // appended after this naturally overrides the conf value.
        confValue(confText, "jvm.memory", playId)?.takeIf { it.isNotBlank() }?.let { mem ->
            mem.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(it) }
        }
        val jmxPort = confValue(confText, "jmx.port", playId)?.takeIf { it.isNotBlank() }
        val jmxHost = confValue(confText, "jmx.hostname", playId)?.takeIf { it.isNotBlank() }
        if (jmxPort != null && jmxHost != null) {
            add("-Dcom.sun.management.jmxremote")
            add("-Dcom.sun.management.jmxremote.port=$jmxPort")
            add("-Dcom.sun.management.jmxremote.ssl=false")
            add("-Dcom.sun.management.jmxremote.authenticate=false")
            add("-Dcom.sun.management.jmxremote.local.only=false")
            add("-Dcom.sun.management.jmxremote.host=$jmxHost")
            add("-Djava.rmi.server.hostname=$jmxHost")
        }
    }
}

// Read the env-var name from `application.secret=${VARNAME}`. Returns
// PLAY_SECRET when the conf is missing or the line uses an unparseable form
// (the framework rejects literals — only `${VAR}` placeholders are valid).
private fun readSecretVarName(appConf: File): String {
    if (!appConf.isFile) return "PLAY_SECRET"
    val pattern = Regex("""^\s*application\.secret\s*=\s*\$\{([^}:]+)\}\s*$""")
    appConf.readLines().forEach { line ->
        val stripped = line.trimStart()
        if (stripped.startsWith("#") || stripped.startsWith("!")) return@forEach
        pattern.matchEntire(line.trimEnd())?.let { return it.groupValues[1] }
    }
    return "PLAY_SECRET"
}

private fun generateSecret(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val rng = SecureRandom()
    return buildString(64) {
        repeat(64) { append(alphabet[rng.nextInt(alphabet.length)]) }
    }
}

// Test commands (playTest, playAutotest) run hermetically and don't care about
// a stable secret value across runs. If the secret variable named by
// application.conf isn't already provided (via certs/.env or the host env),
// synthesize a fresh one so a fresh checkout can run tests without first
// running `play secret`. Returns (varName, freshSecret) when generation
// occurred, or null when the env already provides it.
private fun ensureTestSecret(appDir: File, dotenv: Map<String, String>): Pair<String, String>? {
    val varName = readSecretVarName(File(appDir, "conf/application.conf"))
    if (dotenv.containsKey(varName)) return null
    if (!System.getenv(varName).isNullOrEmpty()) return null
    return varName to generateSecret()
}

/**
 * Resolution order: -Ppid-file=<value>, then application.pidFile in
 * conf/application.conf, then "server.pid". Absolute paths are used as-is;
 * relative paths resolve against the app directory.
 */
private fun resolvePidFile(appDir: File, override: String?): File {
    val name = override?.takeIf { it.isNotBlank() }
        ?: run {
            val configFile = File(appDir, "conf/application.conf")
            if (configFile.isFile) activeValue(configFile.readText(), "application.pidFile") else null
        }
        ?: "server.pid"
    val f = File(name)
    return if (f.isAbsolute) f else File(appDir, name)
}

// Build the SPA if a Nuxt frontend is present at the project root. Runs
// pnpm install + pnpm run generate, then copies frontend/.output/public/ to
// public/spa/ in the project root. Used by both playDist and playBundle so
// the dist-shaped artifacts include the frontend build alongside the
// precompiled Java classes. Returns true if a frontend was found and built.
private fun buildFrontendAndCopySpa(
    projDir: File,
    execOps: ExecOperations,
    logger: Logger,
): Boolean {
    val frontendDir = File(projDir, "frontend")
    if (!frontendDir.isDirectory) return false

    // Probe for pnpm. Without a pre-flight check the user gets a cryptic
    // "Cannot run program 'pnpm'" from ProcessBuilder; we'd rather fail
    // with an actionable message naming the tool and where to install it.
    try {
        execOps.exec {
            commandLine("pnpm", "--version")
            workingDir = frontendDir
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
    } catch (_: Exception) {
        throw GradleException(
            "pnpm not found on PATH but ${frontendDir.absolutePath} is a Nuxt frontend. " +
            "Install pnpm (https://pnpm.io/installation) or remove the frontend directory."
        )
    }

    // Always install. Warm-cache pnpm install is sub-second; skipping when
    // node_modules/ already exists creates a footgun where editing
    // package.json and running play dist ships a stale SPA with no warning.
    logger.lifecycle("~ Running pnpm install in ${frontendDir.absolutePath}")
    execOps.exec {
        commandLine("pnpm", "install")
        workingDir = frontendDir
    }

    logger.lifecycle("~ Running pnpm run generate in ${frontendDir.absolutePath}")
    execOps.exec {
        commandLine("pnpm", "run", "generate")
        workingDir = frontendDir
    }

    val spaSource = File(frontendDir, ".output/public")
    if (!spaSource.isDirectory) {
        throw GradleException("nuxi generate did not produce ${spaSource.absolutePath}")
    }
    val spaDest = File(projDir, "public/spa")
    if (spaDest.exists()) spaDest.deleteRecursively()
    spaSource.copyRecursively(spaDest)
    logger.lifecycle("~ Copied SPA build to ${spaDest.absolutePath}")
    return true
}

// Copy a file into a ZipFileSystem path, preserving POSIX permissions. Used
// by playDist and playBundle to build zips where the source file's
// executable bit (e.g. on shell scripts) survives the round-trip. Plain
// java.util.zip.ZipOutputStream has no public setter for external
// attributes; ZipFileSystem with enablePosixFileAttributes does.
private fun copyToZipfs(source: java.nio.file.Path, dest: java.nio.file.Path) {
    Files.createDirectories(dest.parent)
    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
    try {
        Files.setPosixFilePermissions(dest, Files.getPosixFilePermissions(source))
    } catch (_: UnsupportedOperationException) {
        // Source FS doesn't expose POSIX permissions (Windows host on NTFS).
        // Zip entry keeps its default mode.
    }
}

// Load the bundled-play launcher script template and substitute the
// framework version. The template is shipped as a plugin resource (under
// src/main/resources/bundle-play.sh) so it can be syntax-checked / linted
// independently of the Kotlin source — embedding 150 lines of bash inside
// a Kotlin string would require escaping every `$` as `${'$'}`.
private fun bundlePlayScript(fwVersion: String): String {
    val resource = Play1Plugin::class.java.classLoader
        .getResource("bundle-play.sh")
        ?: throw IllegalStateException("bundle-play.sh resource not found in plugin classpath")
    return resource.readText().replace("__FW_VERSION__", fwVersion)
}

abstract class PlayStartTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val frameworkPath: DirectoryProperty
    @get:Internal abstract val frameworkVersion: Property<String>
    @get:Internal abstract val playId: Property<String>
    @get:Internal abstract val httpPort: Property<Int>
    @get:Internal abstract val playClasspath: ConfigurableFileCollection
    @get:Internal abstract val pidFileOverride: Property<String>
    @get:Internal abstract val extraJvmArgs: Property<String>

    @TaskAction
    fun start() {
        val appDir = applicationPath.get().asFile
        val pidFile = resolvePidFile(appDir, pidFileOverride.orNull)
        if (pidFile.exists()) {
            val existing = pidFile.readText().trim().toLongOrNull()
            if (existing != null && ProcessHandle.of(existing).isPresent) {
                throw GradleException("Oops. ${appDir.absolutePath} is already started (pid:$existing). Stop it first or delete ${pidFile.absolutePath}.")
            }
            logger.lifecycle("~ Removing pid file ${pidFile.absolutePath} for not running pid $existing")
            pidFile.delete()
        }

        val jvmArgsList = extraJvmArgs.orNull
            ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            ?: emptyList()
        val process = spawnPlay(appDir, frameworkPath.get().asFile, frameworkVersion.get(),
            playId.get(), httpPort.orNull, playClasspath.asPath, jvmArgsList)
        pidFile.writeText(process.pid().toString())
        val sysOut = File(appDir, "logs/system.out")
        logger.lifecycle("~ OK, ${appDir.absolutePath} is started")
        logger.lifecycle("~ output is redirected to ${sysOut.absolutePath}")
        logger.lifecycle("~ pid is ${process.pid()}")
    }
}

abstract class PlayStopTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val pidFileOverride: Property<String>

    @TaskAction
    fun stop() {
        val appDir = applicationPath.get().asFile
        val pidFile = resolvePidFile(appDir, pidFileOverride.orNull)
        if (!pidFile.exists()) {
            logger.lifecycle("~ ${appDir.absolutePath} is already stopped")
            return
        }
        val pid = pidFile.readText().trim().toLong()
        val handle = ProcessHandle.of(pid).orElse(null)
        if (handle == null) {
            logger.lifecycle("~ Play was not running (pid $pid not found); removing stale pid file")
        } else {
            handle.destroy()
            handle.onExit().get(10, TimeUnit.SECONDS)
        }
        pidFile.delete()
        logger.lifecycle("~ OK, ${appDir.absolutePath} is stopped")
    }
}

abstract class PlayRestartTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val frameworkPath: DirectoryProperty
    @get:Internal abstract val frameworkVersion: Property<String>
    @get:Internal abstract val playId: Property<String>
    @get:Internal abstract val httpPort: Property<Int>
    @get:Internal abstract val playClasspath: ConfigurableFileCollection
    @get:Internal abstract val pidFileOverride: Property<String>
    @get:Internal abstract val extraJvmArgs: Property<String>

    @TaskAction
    fun restart() {
        val appDir = applicationPath.get().asFile
        val pidFile = resolvePidFile(appDir, pidFileOverride.orNull)
        if (pidFile.exists()) {
            val pid = pidFile.readText().trim().toLongOrNull()
            pidFile.delete()
            if (pid != null) {
                ProcessHandle.of(pid).ifPresent { h ->
                    h.destroy()
                    h.onExit().get(10, TimeUnit.SECONDS)
                }
            }
        } else {
            logger.lifecycle("~ ${appDir.absolutePath} was not started (${pidFile.name} not found); starting fresh")
        }

        val jvmArgsList = extraJvmArgs.orNull
            ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            ?: emptyList()
        val process = spawnPlay(appDir, frameworkPath.get().asFile, frameworkVersion.get(),
            playId.get(), httpPort.orNull, playClasspath.asPath, jvmArgsList)
        pidFile.writeText(process.pid().toString())
        val sysOut = File(appDir, "logs/system.out")
        logger.lifecycle("~ OK, ${appDir.absolutePath} is restarted")
        logger.lifecycle("~ output is redirected to ${sysOut.absolutePath}")
        logger.lifecycle("~ New pid is ${process.pid()}")
    }
}

abstract class PlayPidTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val pidFileOverride: Property<String>

    @TaskAction
    fun showPid() {
        val pidFile = resolvePidFile(applicationPath.get().asFile, pidFileOverride.orNull)
        if (!pidFile.exists()) {
            logger.lifecycle("~ The application is not running")
            return
        }
        val pid = pidFile.readText().trim()
        logger.lifecycle("~ PID of the running application is $pid")
    }
}

abstract class PlayOutTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val pidFileOverride: Property<String>
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun out() {
        val appDir = applicationPath.get().asFile
        if (!resolvePidFile(appDir, pidFileOverride.orNull).exists()) {
            logger.lifecycle("~ The application is not running")
            return
        }
        val sysOut = File(appDir, "logs/system.out")
        if (!sysOut.exists()) {
            logger.lifecycle("~ ${sysOut.absolutePath} not found")
            return
        }
        execOps.exec {
            commandLine("tail", "-f", sysOut.absolutePath)
            standardInput = System.`in`
        }
    }
}

abstract class PlayStatusTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val pidFileOverride: Property<String>
    @get:Internal abstract val httpPort: Property<Int>

    @TaskAction
    fun status() {
        val appDir = applicationPath.get().asFile
        val pidFile = resolvePidFile(appDir, pidFileOverride.orNull)
        if (!pidFile.exists()) {
            logger.lifecycle("~ The application is not running")
            return
        }
        val pid = pidFile.readText().trim().toLongOrNull()
        if (pid == null) {
            logger.lifecycle("~ ${pidFile.absolutePath} is unreadable; the application is likely not running")
            return
        }
        if (!ProcessHandle.of(pid).isPresent) {
            logger.lifecycle("~ Stale pid file ${pidFile.absolutePath}: pid $pid is not running")
            return
        }
        // Process is alive — fetch the JSON dump from /@status. No auth: the
        // endpoint is unauthenticated by design (same posture as /@metrics).
        // Port resolution: -PhttpPort > conf > 9000. The -P override mirrors
        // playStart so users who started on a non-default port can query the
        // same way.
        val configFile = File(appDir, "conf/application.conf")
        val config = if (configFile.isFile) configFile.readText() else ""
        val port = httpPort.orNull
            ?: activeValue(config, "http.port")?.toIntOrNull()
            ?: 9000
        val url = "http://localhost:$port/@status"
        try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            logger.lifecycle("~ Application is running (pid $pid). Status from $url:")
            logger.lifecycle("~")
            println(body)
        } catch (e: java.io.IOException) {
            // Pid is alive but the HTTP port isn't reachable — typically means
            // conf's http.port doesn't match the runtime port (e.g. app started
            // with --http.port=<other>). Report what we know without failing.
            logger.lifecycle("~ Application is running (pid $pid) but cannot reach $url: ${e.message}")
        }
    }
}

private fun spawnPlay(
    appDir: File,
    frameworkPath: File,
    frameworkVersion: String,
    playId: String,
    httpPort: Int?,
    classpath: String,
    extraJvmArgs: List<String> = emptyList(),
): Process {
    val playJar = File(frameworkPath, "framework/play-$frameworkVersion.jar")
    val cmd = buildList {
        add(System.getProperty("java.home") + "/bin/java")
        add("--enable-native-access=ALL-UNNAMED")
        add("-javaagent:${playJar.absolutePath}")
        add("-Dfile.encoding=utf-8")
        add("-Dapplication.path=${appDir.absolutePath}")
        add("-Dplay.id=$playId")
        add("-Dplay.version=$frameworkVersion")
        // PF-92: conf-driven JVM flags (javaagent.path, agentlib, jvm.memory,
        // jmx.{port,hostname}) lifted from application.conf with %<playId>.
        // priority. Comes before extraJvmArgs so a user's -PjvmArgs overrides
        // a conf value under JVM last-wins semantics.
        addAll(confJvmArgs(appDir, playId))
        // 1.12-style JVM tuning flags (forwarded by the play wrapper via
        // -PjvmArgs); inserted before -classpath so a user-supplied
        // -classpath would override ours, matching `java`'s last-wins.
        addAll(extraJvmArgs)
        add("-classpath")
        add(classpath)
        add("play.server.Server")
        if (httpPort != null) add("--http.port=$httpPort")
    }
    val logsDir = File(appDir, "logs").apply { mkdirs() }
    val sysOut = File(logsDir, "system.out")
    val pb = ProcessBuilder(cmd)
        .directory(appDir)
        .redirectOutput(sysOut)
        .redirectErrorStream(true)
    loadDotEnv(File(appDir, "certs/.env")).forEach { (k, v) ->
        pb.environment().putIfAbsent(k, v)
    }
    return pb.start()
}

abstract class PlayJavadocTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val frameworkPath: DirectoryProperty
    @get:Internal abstract val frameworkVersion: Property<String>
    @get:Internal abstract val modules: ListProperty<String>
    @get:Internal abstract val playClasspath: ConfigurableFileCollection
    @get:Internal abstract val withLinks: Property<Boolean>
    @get:Internal abstract val includeModules: Property<Boolean>

    @get:Inject abstract val execOps: ExecOperations
    @get:Inject abstract val fileSystemOps: FileSystemOperations

    @TaskAction
    fun generate() {
        val appDir = applicationPath.get().asFile
        val outDir = File(appDir, "javadoc")
        fileSystemOps.delete { delete(outDir) }
        outDir.mkdirs()

        val configFile = File(appDir, "conf/application.conf")
        val appName = if (configFile.isFile) {
            activeValue(configFile.readText(), "application.name") ?: "Application"
        } else "Application"

        val sources = mutableListOf<File>()
        listOf(File(appDir, "app"), File(appDir, "src")).forEach { dir ->
            if (dir.isDirectory) collectJavaFiles(dir, sources)
        }
        if (includeModules.getOrElse(false)) {
            val frameworkModulesDir = File(frameworkPath.get().asFile, "modules")
            modules.get().forEach { moduleName ->
                val moduleDir = File(frameworkModulesDir, moduleName)
                if (moduleDir.isDirectory) {
                    listOf(File(moduleDir, "app"), File(moduleDir, "src")).forEach { dir ->
                        if (dir.isDirectory) collectJavaFiles(dir, sources)
                    }
                }
            }
        }
        if (sources.isEmpty()) {
            logger.lifecycle("~ No Java sources found to document")
            return
        }

        val cmd = mutableListOf<String>().apply {
            add(javadocExecutable())
            addAll(listOf("-d", outDir.absolutePath))
            addAll(listOf("-classpath", playClasspath.asPath))
            addAll(listOf("-encoding", "UTF-8", "-charset", "UTF-8"))
            addAll(listOf("--enable-preview", "--source", "25"))
            addAll(listOf("-doctitle", appName))
            addAll(listOf("-header", "<b>$appName</b>"))
            addAll(listOf("-footer", "<b>$appName</b>"))
            if (withLinks.getOrElse(false)) {
                addAll(listOf("-link", "https://docs.oracle.com/en/java/javase/25/docs/api/"))
                addAll(listOf("-link", "https://www.playframework.com/documentation/${frameworkVersion.get()}/api/"))
            }
            addAll(sources.map { it.absolutePath })
        }

        logger.lifecycle("~ Generating Javadoc in ${outDir.absolutePath}...")
        val logsDir = File(appDir, "logs").apply { mkdirs() }
        val outLog = File(logsDir, "javadoc.log")
        val errLog = File(logsDir, "javadoc.err")
        val exitCode = outLog.outputStream().use { sout ->
            errLog.outputStream().use { serr ->
                execOps.exec {
                    commandLine(cmd)
                    standardOutput = sout
                    errorOutput = serr
                    isIgnoreExitValue = true
                }.exitValue
            }
        }

        if (exitCode != 0) {
            throw GradleException("Unable to create Javadocs. See ${errLog.absolutePath} for errors.")
        }
        logger.lifecycle("~ Done! Open ${File(outDir, "overview-tree.html").absolutePath} in your browser.")
    }

    private fun collectJavaFiles(root: File, out: MutableList<File>) {
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.forEach { out.add(it) }
    }

    private fun javadocExecutable(): String {
        val javaHome = System.getenv("JAVA_HOME")
        if (!javaHome.isNullOrBlank()) {
            return File(javaHome, "bin/javadoc").absolutePath
        }
        val home = System.getProperty("java.home")
        val candidate = File(home, "bin/javadoc")
        return if (candidate.exists()) candidate.absolutePath else "javadoc"
    }
}

abstract class PlayBundleTask : DefaultTask() {
    @get:Internal abstract val projectDir: DirectoryProperty
    @get:Internal abstract val projectName: Property<String>
    @get:Internal abstract val frameworkPath: DirectoryProperty
    @get:Internal abstract val frameworkVersion: Property<String>
    @get:Internal abstract val playClasspath: ConfigurableFileCollection
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun bundle() {
        val projDir = projectDir.get().asFile
        val outFile = outputFile.get().asFile
        val appName = projectName.get()
        val fwDir = frameworkPath.get().asFile
        val fwVersion = frameworkVersion.get()

        val frameworkJar = File(fwDir, "framework/play-$fwVersion.jar")
        if (!frameworkJar.isFile) {
            throw GradleException("Framework jar not found at ${frameworkJar.absolutePath}. Did you run `ant jar` in the framework?")
        }
        val frameworkLibDir = File(fwDir, "framework/lib")

        // Same dist-shaped frontend build as playDist: pnpm install + generate,
        // copy frontend/.output/public/ to public/spa/ for static-asset serving.
        buildFrontendAndCopySpa(projDir, execOps, logger)

        // Resolve dep jars: Gradle-resolved minus framework/ + projDir/lib +
        // projDir/modules (those are bundled separately under their respective
        // trees so the .classpath file points at known relative paths).
        val gradleResolvedDeps = playClasspath.files.filter { f ->
            f.isFile && f.name.endsWith(".jar") &&
                !f.absolutePath.startsWith(fwDir.absolutePath) &&
                !f.absolutePath.startsWith(File(projDir, "lib").absolutePath) &&
                !f.absolutePath.startsWith(File(projDir, "modules").absolutePath)
        }.distinctBy { it.name }

        val gradleResolvedNames = gradleResolvedDeps.map { it.name }.toSet()
        val appLibJars = File(projDir, "lib").let { dir ->
            if (dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }
                    .filter { it.name !in gradleResolvedNames }
                    .distinctBy { it.name }
                    .toList()
            } else emptyList()
        }

        // Module lib jars — modules/ is typically untracked (extracted by
        // extractPlayModules at build time), so we explicitly walk and add them.
        val moduleLibJars = mutableListOf<Pair<File, String>>()
        File(projDir, "modules").let { modulesDir ->
            if (modulesDir.isDirectory) {
                modulesDir.listFiles()?.filter { it.isDirectory }?.forEach { moduleDir ->
                    File(moduleDir, "lib").let { libDir ->
                        if (libDir.isDirectory) {
                            libDir.walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }.forEach { jar ->
                                val rel = "modules/${moduleDir.name}/lib/${jar.name}"
                                moduleLibJars.add(jar to rel)
                            }
                        }
                    }
                }
            }
        }

        // Compute classpath entries (relative to bundle root, one per line in
        // .classpath). The bundled `play` script reads this at startup to
        // assemble the runtime classpath.
        val classpathEntries = mutableListOf<String>()
        classpathEntries += "conf"
        classpathEntries += "framework/play-$fwVersion.jar"
        if (frameworkLibDir.isDirectory) {
            frameworkLibDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.sortedBy { it.name }?.forEach {
                classpathEntries += "framework/lib/${it.name}"
            }
        }
        appLibJars.sortedBy { it.name }.forEach { jar ->
            classpathEntries += jar.relativeTo(projDir).path.replace(File.separatorChar, '/')
        }
        gradleResolvedDeps.sortedBy { it.name }.forEach {
            classpathEntries += "lib/${it.name}"
        }
        moduleLibJars.sortedBy { it.second }.forEach { (_, rel) ->
            classpathEntries += rel
        }

        // Source file list (dist-style):
        //   - git ls-files for source (respects .gitignore everywhere, including
        //     frontend/.gitignore which keeps node_modules/.output out of the zip)
        //   - PLUS explicit walk of precompiled/ and public/spa/ to force-include
        //     build outputs that are typically gitignored
        // We then filter out paths under lib/, modules/, framework/ — those are
        // populated from the explicit jar lists below, so re-walking them via
        // git ls-files would duplicate entries.
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

        val forcedRoots = listOf("precompiled", "public/spa")
            .map { File(projDir, it) }
            .filter { it.isDirectory }
        val forcedFiles = forcedRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(projDir).path.replace(File.separatorChar, '/') }
                .toList()
        }
        val bundleOwnedPrefixes = listOf("lib/", "modules/", "framework/")
        val sourceFiles = (gitFiles + forcedFiles)
            .distinct()
            .filterNot { rel -> bundleOwnedPrefixes.any { rel.startsWith(it) } }
            .sorted()

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

        // Write the bundle zip via JDK ZipFileSystem so POSIX perms (executable
        // bit on shell scripts, 0644 vs 0755) survive the round-trip.
        val zipPath = outFile.toPath()
        val env = mapOf("create" to "true", "enablePosixFileAttributes" to "true")
        FileSystems.newFileSystem(zipPath, env).use { zipfs ->
            // 1. Source files (preserve source POSIX perms).
            for (relpath in sourceFiles) {
                if (outRelPrefix != null && relpath.startsWith(outRelPrefix)) continue
                if (ignorePrefixes.any { relpath.startsWith(it) }) continue
                val srcFile = projDir.resolve(relpath)
                if (!srcFile.isFile) continue
                copyToZipfs(srcFile.toPath(), zipfs.getPath("/$appName/$relpath"))
            }

            // 2. Framework jar.
            copyToZipfs(frameworkJar.toPath(), zipfs.getPath("/$appName/framework/play-$fwVersion.jar"))

            // 3. Framework lib jars.
            if (frameworkLibDir.isDirectory) {
                frameworkLibDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.sortedBy { it.name }?.forEach { jar ->
                    copyToZipfs(jar.toPath(), zipfs.getPath("/$appName/framework/lib/${jar.name}"))
                }
            }

            // 4. Gradle-resolved deps under lib/.
            gradleResolvedDeps.sortedBy { it.name }.forEach { jar ->
                copyToZipfs(jar.toPath(), zipfs.getPath("/$appName/lib/${jar.name}"))
            }

            // 5. Manually-placed app lib jars (uncommon — clean Gradle apps have none).
            appLibJars.sortedBy { it.name }.forEach { jar ->
                copyToZipfs(jar.toPath(), zipfs.getPath("/$appName/lib/${jar.name}"))
            }

            // 6. Module lib jars (extracted by extractPlayModules; typically
            //    untracked, so source file collection wouldn't include them).
            moduleLibJars.forEach { (jar, rel) ->
                copyToZipfs(jar.toPath(), zipfs.getPath("/$appName/$rel"))
            }

            // 7. .classpath — one entry per line, used by the bundled `play`
            //    script to assemble the runtime classpath at startup.
            val classpathPath = zipfs.getPath("/$appName/.classpath")
            Files.createDirectories(classpathPath.parent)
            Files.writeString(classpathPath, classpathEntries.joinToString("\n") + "\n")

            // 8. Bundled `play` runtime launcher (executable). Mirrors the dev-
            //    time shim's CLI surface (run/start/stop/restart/status/pid/out)
            //    but dispatches to a direct java exec — no gradle needed at
            //    runtime. Writes to /$appName/play, overriding any user-tracked
            //    file at that path. Marked +x explicitly so unzip preserves it.
            val playPath = zipfs.getPath("/$appName/play")
            Files.createDirectories(playPath.parent)
            Files.writeString(playPath, bundlePlayScript(fwVersion))
            try {
                Files.setPosixFilePermissions(playPath, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
                ))
            } catch (_: UnsupportedOperationException) { /* non-POSIX FS */ }
        }
        logger.lifecycle("Bundle created at ${outFile.absolutePath} (${outFile.length() / 1024 / 1024} MB)")
        logger.lifecycle("~ Self-contained — runtime requires only Java 25+. Unzip and run:")
        logger.lifecycle("~     cd $appName && ./play start --%prod")
    }
}


abstract class PlayModulesInfoTask : DefaultTask() {
    @get:Internal abstract val applicationPath: DirectoryProperty
    @get:Internal abstract val frameworkPath: DirectoryProperty
    @get:Internal abstract val playId: Property<String>

    @TaskAction
    fun show() {
        val appDir = applicationPath.get().asFile
        val fwDir = frameworkPath.get().asFile
        val configFile = File(appDir, "conf/application.conf")
        val configText = if (configFile.isFile) configFile.readText() else ""

        val modules = linkedSetOf<File>()

        // 1. dev mode: framework auto-adds docviewer
        val mode = (activeValue(configText, "application.mode") ?: "dev").lowercase()
        if (mode == "dev") {
            val docviewer = File(fwDir, "modules/docviewer")
            if (docviewer.isDirectory) modules.add(docviewer)
        }

        // 2. module.X=path entries from application.conf
        Regex("""^\s*module\.([^=\s]+)\s*=\s*(.+?)\s*$""", RegexOption.MULTILINE)
            .findAll(configText)
            .forEach { match ->
                val raw = match.groupValues[2].trim()
                val resolved = raw.replace("\${play.path}", fwDir.absolutePath)
                val f = if (resolved.startsWith("/")) File(resolved) else File(appDir, resolved)
                if (f.isDirectory) modules.add(f)
            }

        // 3. app's own modules/*
        val localModules = File(appDir, "modules")
        if (localModules.isDirectory) {
            localModules.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.forEach { modules.add(it) }
        }

        // 4. test mode: framework auto-adds testrunner (Play.runningInTestMode matches play.id ~ test|test-?.*)
        if (playId.getOrElse("").startsWith("test")) {
            val testrunner = File(fwDir, "modules/testrunner")
            if (testrunner.isDirectory) modules.add(testrunner)
        }

        if (modules.isEmpty()) {
            logger.lifecycle("~ No modules installed in this application")
        } else {
            logger.lifecycle("~ Application modules are:")
            logger.lifecycle("~ ")
            modules.forEach { logger.lifecycle("~ ${it.absolutePath}") }
        }
    }
}
