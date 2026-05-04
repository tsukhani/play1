import java.io.File
import java.security.SecureRandom

group = "org.playframework"
version = "1.13.0-SNAPSHOT"

abstract class PlayNewAppTask : DefaultTask() {
    @get:org.gradle.api.tasks.Internal abstract val frameworkPath: org.gradle.api.file.DirectoryProperty
    @get:org.gradle.api.tasks.Internal abstract val frameworkVersion: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val appName: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val destDir: org.gradle.api.provider.Property<String>

    @get:javax.inject.Inject abstract val fileSystemOps: org.gradle.api.file.FileSystemOperations
    @get:javax.inject.Inject abstract val execOps: org.gradle.process.ExecOperations

    @org.gradle.api.tasks.TaskAction
    fun scaffold() {
        val name = appName.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Required: -Pname=<app-name>")
        val destPath = destDir.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Required: -Pdest=<path-to-new-app>")
        val dest = File(destPath).absoluteFile
        if (dest.exists()) throw GradleException("Destination already exists: ${dest.absolutePath}")

        val skel = frameworkPath.get().dir("resources/application-skel").asFile
        if (!skel.isDirectory) throw GradleException("application-skel not found at ${skel.absolutePath}")

        logger.lifecycle("~ Scaffolding $name at ${dest.absolutePath}")
        fileSystemOps.copy {
            from(skel)
            into(dest)
            // Default copy spec excludes dot-files by default in some scenarios;
            // be explicit by *not* setting any excludes. .gitignore is included.
        }
        File(dest, "app/models").mkdirs()
        File(dest, "lib").mkdirs()

        // Substitute %APPLICATION_NAME% in conf/application.conf
        val appConf = File(dest, "conf/application.conf")
        appConf.writeText(appConf.readText().replace("%APPLICATION_NAME%", name))

        // Generate a 64-char secret and write to certs/.env (chmod 0600) +
        // certs/.env.example template. Mirrors framework/pym/play/utils.py:secretKey/
        // writeAppSecret/writeEnvExample.
        val secret = generateSecret()
        val envFile = File(dest, "certs/.env").apply { parentFile.mkdirs() }
        envFile.writeText("PLAY_SECRET=$secret\n")
        try {
            java.nio.file.Files.setPosixFilePermissions(envFile.toPath(),
                java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX FS, skip
        }
        File(dest, "certs/.env.example").writeText(buildString {
            appendLine("# Environment variables for this Play application.")
            appendLine("#")
            appendLine("# Copy this file to `certs/.env` (which is gitignored) and fill in real values:")
            appendLine("#     cp certs/.env.example certs/.env")
            appendLine("#")
            appendLine("# This template lists every variable the app needs at startup. Keep it in")
            appendLine("# version control so onboarding teammates know what to set. Do NOT put real")
            appendLine("# secrets here -- only placeholders or empty values.")
            appendLine("#")
            appendLine("# At runtime, the gradle plugin loads `certs/.env` into the JVM environment")
            appendLine("# before starting Play. Values already set in the host environment take")
            appendLine("# precedence.")
            appendLine()
            appendLine("# The application secret used for HMAC signing (sessions, CSRF) and AES")
            appendLine("# encryption. Regenerate via `gradle playSecret` (TBD) or any 64-char string.")
            appendLine("PLAY_SECRET=")
        })

        // Generate Gradle build files
        val fwPath = frameworkPath.get().asFile.absolutePath
        val fwVer = frameworkVersion.get()
        File(dest, "settings.gradle.kts").writeText("""
            pluginManagement {
                includeBuild("$fwPath")
            }
            rootProject.name = "$name"
        """.trimIndent() + "\n")
        File(dest, "build.gradle.kts").writeText("""
            plugins {
                id("org.playframework.play1")
            }

            play1 {
                frameworkPath.set(file("$fwPath"))
                frameworkVersion.set("$fwVer")
                httpPort.set(9000)
            }
        """.trimIndent() + "\n")

        // git init so playDist works
        try {
            execOps.exec {
                commandLine("git", "init", "-q")
                workingDir = dest
            }
        } catch (e: Exception) {
            logger.warn("~ git init failed (${e.message}); playDist will require manual git setup")
        }

        logger.lifecycle("~")
        logger.lifecycle("~ OK, the application is created.")
        logger.lifecycle("~ Start it with: cd ${dest.absolutePath} && gradle playRun")
        logger.lifecycle("~")
    }

    private fun generateSecret(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rng = SecureRandom()
        return buildString(64) {
            repeat(64) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
    }
}

tasks.register<PlayNewAppTask>("playNewApp") {
    group = "play1"
    description = "Scaffold a new Play 1 application. Required: -Pname=<name> -Pdest=<path>"
    frameworkPath.set(layout.projectDirectory)
    frameworkVersion.set(version.toString())
    appName.set(providers.gradleProperty("name").orElse(""))
    destDir.set(providers.gradleProperty("dest").orElse(""))
}
