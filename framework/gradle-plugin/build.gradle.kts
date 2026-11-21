plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

// Group + version are inherited from the root project, which derives version
// from framework/build.xml's baseversion (single source of truth).
group = "org.playframework"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    // gradleTestKit() provides GradleRunner. java-gradle-plugin auto-generates
    // the plugin-under-test metadata, so withPluginClasspath() injects the
    // plugin with no manual classpath wiring (PF-139).
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// PF-219: the suite's classes run in parallel forks (maxParallelForks, whose default is 1),
// except the tests tagged "timing", which go wrong under the forks' load: bounds of a few tens of
// milliseconds, and the two configuration-cache cases that a changed file must reconfigure, which
// the forks' file churn sometimes defeats. timingTest runs those first, alone. Gradle's testing
// guide asks of parallel forks only that tests be isolated from one another; each class here
// works in its own @TempDir and picks free ports. The forks share TestKit's directory and its
// Gradle user home, as Gradle processes share one.
val timingTest = tasks.register<Test>("timingTest") {
    description = "Runs the plugin tests tagged timing, on their own (PF-219)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("timing") }
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("timing") }
    // Half the cores, measured on 14 (2026-11-18): 1 fork 3m15s, 2 2m22s, 4 2m06s, 7 1m49s. A class
    // runs in one fork, so the longest, BundleLauncherTest at about 84 s, bounds the wall time,
    // and 7 forks reach that; more would only add load, which already slows each class.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    dependsOn(timingTest)
}

// PF-227: the bash BundleLauncherTest runs the bundle launcher under, /bin/bash unless
// -PlauncherBash names another. The launcher starts through #!/usr/bin/env bash, so without this
// the first bash on the PATH ran it, and on Linux /bin/bash is bash 5 while a stock Mac runs a
// bundle under bash 3.2.57; bash32/test.sh runs the class again under a bash 3.2.57 built for it.
// A system property, so a change of bash reruns the suite rather than finding it up to date.
// Windows starts the launcher through Git Bash and ignores it.
val launcherBash = providers.gradleProperty("launcherBash").getOrElse("/bin/bash")
val onWindows = System.getProperty("os.name").startsWith("Windows")

tasks.withType<Test>().configureEach {
    if (!onWindows) {
        // A local, because the configuration cache cannot store an action holding the script.
        val bash = launcherBash
        systemProperty("play.test.launcherBash", bash)
        doFirst {
            val version = runCatching {
                val probe = ProcessBuilder(bash, "-c", "echo \$BASH_VERSION").redirectErrorStream(true).start()
                probe.inputStream.bufferedReader().readText().trim().takeIf { probe.waitFor() == 0 }
            }.getOrNull()
            if (version.isNullOrEmpty()) throw GradleException("launcherBash $bash does not run as a bash")
            logger.lifecycle("BundleLauncherTest runs the bundle launcher under $bash, bash $version")
        }
    }
    // FrameworkBomsTest reads the repo's real lib-coordinates.properties, which `ant jar`
    // rewrites after every `ant resolve`; undeclared, the suite stayed UP-TO-DATE across a
    // dependency bump. inputs.files, not inputs.file: a checkout that has never run `ant jar`
    // has no such file (the test skips itself then), and Gradle fails a task whose declared
    // input file is missing, even an optional one; a collection records it as missing instead.
    inputs.files(rootProject.layout.projectDirectory.file("framework/lib-coordinates.properties"))
        .withPropertyName("libCoordinates")
    // PF-230: TestJvmsTest drives an application's test task through the Tooling API, as an IDE
    // does, with the Gradle that runs this build.
    systemProperty("play.test.gradleHome", gradle.gradleHomeDir!!.absolutePath)
    // PF-206: AutotestVerdictTest compiles the real FirePhoque from the testrunner module and
    // runs it with that module's htmlunit jars, so a change there has to rerun the suite.
    inputs.files(
        rootProject.layout.projectDirectory.dir("modules/testrunner/src"),
        rootProject.layout.projectDirectory.dir("modules/testrunner/firephoque"),
    ).withPropertyName("testrunnerSources")
}

// PF-207: an application's pnpm follows the framework's, which is the skel's packageManager
// pin. The jar carries the skel's package.json so that an application follows the release it
// builds against, not whatever a working copy of the fork holds at the moment.
tasks.processResources {
    from(rootProject.layout.projectDirectory.file("resources/nuxt-skel/package.json")) {
        into("play/gradle")
        rename { "nuxt-skel-package.json" }
    }
    // PF-216: an application's Gradle wrapper follows the framework's, for the same reason. The
    // properties name the distribution and its checksum; the jar and the scripts are what
    // playGradleSync writes beside them, as playNewApp copies all four into a new application.
    // One file at a time: a from() of the repo root would have Gradle walk the whole checkout.
    listOf(
        "gradle/wrapper/gradle-wrapper.properties",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradlew",
        "gradlew.bat",
    ).forEach { path ->
        from(rootProject.layout.projectDirectory.file(path)) { into("play/gradle/wrapper") }
    }
}

gradlePlugin {
    plugins {
        register("play1") {
            id = "org.playframework.play1"
            implementationClass = "play.gradle.Play1Plugin"
            displayName = "Play 1 Gradle Plugin"
            description = "Builds and runs Play Framework 1.x applications via Gradle"
        }
    }
}

// Publish into a flat Maven repo under the framework distribution. Apps
// generated by playNewApp reference this repo from pluginManagement.repositories,
// so they resolve id("org.playframework.play1") without needing to compile
// the plugin from source via includeBuild() — that model breaks on read-only
// installs (e.g. one owned by root) because compiling the plugin writes
// into the included build's gradle-plugin/build/ directory.
publishing {
    repositories {
        maven {
            name = "FrameworkLocal"
            url = uri(rootProject.layout.projectDirectory.dir("framework/gradle-plugin-repo"))
        }
    }
}
