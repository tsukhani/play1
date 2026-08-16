import java.io.File
import java.security.SecureRandom

group = "org.playframework"
// Single source of truth: framework/build.xml's baseversion. This is the
// version stamped into play-<version>.jar by Ant; reading it here means
// playNewApp injects the matching version into generated apps' build.gradle.kts
// (and `gradle playVersion` reports the same), with no manual sync needed
// across version bumps.
version = run {
    val buildXml = file("framework/build.xml")
    val match = Regex("""name="baseversion"\s+value="([^"]+)"""").find(buildXml.readText())
        ?: error("Could not find baseversion property in ${buildXml.absolutePath}")
    match.groupValues[1]
}

abstract class PlayNewAppTask : DefaultTask() {
    @get:org.gradle.api.tasks.Internal abstract val frameworkPath: org.gradle.api.file.DirectoryProperty
    @get:org.gradle.api.tasks.Internal abstract val frameworkVersion: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val appName: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val destDir: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val withFrontend: org.gradle.api.provider.Property<Boolean>

    @get:javax.inject.Inject abstract val fileSystemOps: org.gradle.api.file.FileSystemOperations
    @get:javax.inject.Inject abstract val execOps: org.gradle.process.ExecOperations

    @org.gradle.api.tasks.TaskAction
    fun scaffold() {
        // Two derived names from -Pname:
        //   displayName: as-given (preserves spaces/case). Used for application.name
        //                in conf/application.conf — Play renders this in welcome page,
        //                logs, error pages.
        //   identifier:  lowercased + alphanumeric-only slug. Used for rootProject.name
        //                in settings.gradle.kts (Gradle wants a single token), the
        //                default dest dir name, and any other Gradle/filesystem id.
        val displayName = appName.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Required: -Pname=<app-name>")
        val identifier = displayName.lowercase().filter { it.isLetterOrDigit() }
        if (identifier.isEmpty()) {
            throw GradleException("-Pname must contain at least one letter or digit (got '$displayName')")
        }
        val destPath = destDir.orNull?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.dir"), identifier).absolutePath
        val dest = File(destPath).absoluteFile
        if (dest.exists()) throw GradleException("Destination already exists: ${dest.absolutePath}")

        val skel = frameworkPath.get().dir("resources/application-skel").asFile
        if (!skel.isDirectory) throw GradleException("application-skel not found at ${skel.absolutePath}")

        logger.lifecycle("~ Scaffolding \"$displayName\" (identifier: $identifier) at ${dest.absolutePath}")
        // Plain JVM walk-and-copy. We can't use fileSystemOps.copy here because
        // Gradle's default Ant excludes silently drop .gitignore, .gitattributes,
        // and other dot-files we want in scaffolded apps. We still skip a small
        // set of well-known junk files (macOS metadata, editor backups).
        skel.walkTopDown().forEach { src ->
            val rel = src.toRelativeString(skel)
            if (rel.isEmpty()) return@forEach
            if (src.name == ".DS_Store" || src.name == "Thumbs.db") return@forEach
            if (src.name.endsWith("~")) return@forEach
            val target = File(dest, rel)
            if (src.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = false)
            }
        }
        File(dest, "app/models").mkdirs()

        // Substitute %APPLICATION_NAME% with the displayName (Play wants the
        // human-readable form, not the slug).
        val appConf = File(dest, "conf/application.conf")
        appConf.writeText(appConf.readText().replace("%APPLICATION_NAME%", displayName))

        // Generate a 64-char secret and write to certs/.env (chmod 0600) +
        // certs/.env.example template. Same shape as the playSecret task.
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
        // Resolve the play1 plugin from the framework's pre-published local
        // Maven repo (framework/gradle-plugin-repo). Earlier 1.13.x releases
        // used pluginManagement.includeBuild("$fwPath"), which builds the plugin
        // from source — that requires writing into $fwPath/.gradle/ and
        // $fwPath/framework/gradle-plugin/build/, which fails on read-only
        // installs (e.g. /opt/play1 owned by root). Resolving from a flat
        // Maven repo only reads from $fwPath, so the framework can stay
        // read-only.
        File(dest, "settings.gradle.kts").writeText("""
            pluginManagement {
                repositories {
                    maven {
                        url = uri("file://$fwPath/framework/gradle-plugin-repo")
                    }
                    gradlePluginPortal()
                }
            }
            rootProject.name = "$identifier"
        """.trimIndent() + "\n")
        // Enable Gradle's configuration cache by default. The play-gradle-plugin is
        // designed to be config-cache safe (every task uses @Inject services + Property
        // inputs, no Task.project at execution time), so the speedup is free.
        // Becomes the only mode in Gradle 10.
        File(dest, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")
        File(dest, "build.gradle.kts").writeText("""
            plugins {
                id("org.playframework.play1") version "$fwVer"
            }

            play1 {
                frameworkPath.set(file("$fwPath"))
                frameworkVersion.set("$fwVer")

                // Docviewer powers the welcome page and the /@docs (user docs + Javadoc) browser
                // in dev mode. Remove if you want a slimmer setup.
                modules("docviewer")
            }
        """.trimIndent() + "\n")

        // Copy the Gradle wrapper from the framework so the new app can run
        // ./gradlew (and `play <cmd>`, which dispatches to ./gradlew) without
        // requiring system-installed Gradle. Pinning the wrapper to the
        // framework's Gradle version also gives reproducible builds.
        //
        // Manual JVM copy (not fileSystemOps.copy) for the same reason as the
        // skel walk-and-copy above: Gradle's CopySpec-with-include() behaves
        // unpredictably when the source root is a Property<Directory> resolved
        // from -PframeworkPath (the play wrapper sets this when invoking from a
        // scratch project dir on read-only installs), occasionally treating the
        // wrapper subdirectory as a single file and aborting with "Failed to
        // create directory". Walking with java.io.File doesn't have that quirk.
        val fwFile = frameworkPath.get().asFile
        listOf("gradlew", "gradlew.bat").forEach { name ->
            val src = File(fwFile, name)
            if (src.isFile) src.copyTo(File(dest, name), overwrite = false)
        }
        val srcWrapper = File(fwFile, "gradle/wrapper")
        if (srcWrapper.isDirectory) {
            val dstWrapper = File(dest, "gradle/wrapper").apply { mkdirs() }
            srcWrapper.listFiles()?.forEach { f ->
                if (f.isFile) f.copyTo(File(dstWrapper, f.name), overwrite = false)
            }
        }
        File(dest, "gradlew").setExecutable(true)

        // git init so playDist works
        try {
            execOps.exec {
                commandLine("git", "init", "-q")
                workingDir = dest
            }
        } catch (e: Exception) {
            logger.warn("~ git init failed (${e.message}); playDist will require manual git setup")
        }

        val frontend = withFrontend.getOrElse(false)
        if (frontend) {
            setupNuxtFrontend(dest, displayName, identifier)
        }

        logger.lifecycle("~")
        logger.lifecycle("~ OK, the application is created.")
        logger.lifecycle("~")
        logger.lifecycle("~ Start it with:")
        logger.lifecycle("~     cd ${dest.absolutePath}")
        logger.lifecycle("~     play run")
        logger.lifecycle("~")
        logger.lifecycle("~ Open it in your IDE:")
        logger.lifecycle("~     Point IntelliJ IDEA / VS Code / Eclipse at:")
        logger.lifecycle("~         ${dest.absolutePath}/build.gradle.kts")
        logger.lifecycle("~     Modern IDEs auto-import this project with no extra setup.")
        if (frontend) {
            logger.lifecycle("~")
            logger.lifecycle("~ Start the frontend with:")
            logger.lifecycle("~     cd ${dest.absolutePath}/frontend && pnpm install && pnpm dev")
        }
        logger.lifecycle("~")
    }

    private fun setupNuxtFrontend(dest: File, displayName: String, identifier: String) {
        val nuxtSkel = frameworkPath.get().dir("resources/nuxt-skel").asFile
        if (!nuxtSkel.isDirectory) throw GradleException("nuxt-skel not found at ${nuxtSkel.absolutePath}")

        val frontendDir = File(dest, "frontend")
        logger.lifecycle("~")
        logger.lifecycle("~ Setting up Nuxt 4 frontend...")

        // Manual walk-and-copy (same reasoning as the gradle-wrapper copy
        // above): fileSystemOps.copy preserves source-file permissions, so
        // copying from a read-only install (e.g. root-owned /opt/play1) lands
        // read-only files in dest, then chokes on its own subsequent writes
        // into dest subdirectories. java.io.File.copyTo creates dest files
        // under the user's umask regardless of source perms.
        nuxtSkel.walkTopDown().forEach { src ->
            val rel = src.toRelativeString(nuxtSkel)
            if (rel.isEmpty()) return@forEach
            val target = File(frontendDir, rel)
            if (src.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = false)
            }
        }

        // Move ApiController.java from frontend/server/ to app/controllers/.
        // The skel keeps it under server/ so it's clear at a glance that the
        // file is server-side Java, not part of the Nuxt frontend tree.
        val apiCtrlSrc = File(frontendDir, "server/ApiController.java")
        val apiCtrlDst = File(dest, "app/controllers/ApiController.java")
        if (apiCtrlSrc.exists()) {
            apiCtrlSrc.renameTo(apiCtrlDst)
            apiCtrlSrc.parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
        }

        // Substitute placeholders in text files under frontend/.
        // %APPLICATION_NAME%       → human-readable form (page titles, banners)
        // %APPLICATION_IDENTIFIER% → npm-package-style slug (package.json's "name"
        //                            field rejects spaces and uppercase)
        frontendDir.walkTopDown().filter { it.isFile }.forEach { f ->
            try {
                val original = f.readText()
                val updated = original
                    .replace("%APPLICATION_NAME%", displayName)
                    .replace("%APPLICATION_IDENTIFIER%", identifier)
                if (updated != original) f.writeText(updated)
            } catch (_: Exception) {
                // Binary file or unreadable; skip
            }
        }

        // Patch conf/routes: insert /api/status route before "# Catch all"
        val routesFile = File(dest, "conf/routes")
        if (routesFile.exists()) {
            val routes = routesFile.readText()
            val apiRoutes = "# API endpoints for Nuxt frontend\nGET     /api/status                ApiController.status\n\n"
            if (routes.contains("# Catch all\n")) {
                routesFile.writeText(routes.replace("# Catch all\n", apiRoutes + "# Catch all\n"))
            }
        }

        // Frontend gitignore
        File(frontendDir, ".gitignore").writeText("node_modules\n.nuxt\n.output\ndist\n")

        logger.lifecycle("~ Nuxt 4 frontend created in ${frontendDir.absolutePath}")
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
    description = "Scaffold a new Play 1 application. Required: -Pname=<name>. Optional: -Pdest=<path> (default: <cwd>/<name>), -Pfrontend (add Nuxt 4 frontend), -PframeworkPath=<path> (override; defaults to projectDir — used by the play wrapper to invoke this task from a writable scratch dir)"
    val fwPathProp = providers.gradleProperty("frameworkPath").orNull?.takeIf { it.isNotBlank() }
    if (fwPathProp != null) {
        frameworkPath.set(file(fwPathProp))
    } else {
        frameworkPath.set(layout.projectDirectory)
    }
    frameworkVersion.set(version.toString())
    appName.set(providers.gradleProperty("name").orElse(""))
    destDir.set(providers.gradleProperty("dest").orElse(""))
    withFrontend.set(providers.gradleProperty("frontend").map { true }.orElse(false))
}
