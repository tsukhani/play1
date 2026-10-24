// PF-195: OWASP dependency-check over every jar the release zip ships, failing at CVSS 7.0 after
// ../dependency-check-suppressions.xml. Run it as `ant dependency-check`, which builds
// play-<version>.jar first.
plugins {
    // In step with the applications' plugin: the NVD database below is shared with their scans,
    // and a dependency-check release may change its schema.
    id("org.owasp.dependencycheck") version "12.2.2"
}

val framework = projectDir.parentFile
val repo = framework.parentFile
// The version the root build stamps on the gradle-plugin jar, read the same way.
val playVersion = Regex("""name="baseversion"\s+value="([^"]+)"""").find(framework.resolve("build.xml").readText())
    ?.groupValues?.get(1) ?: error("no baseversion in ${framework.resolve("build.xml")}")

dependencyCheck {
    // The directories `ant artifact` copies the zip's jars from, and the gradle-plugin jar that
    // `ant package` publishes into framework/gradle-plugin-repo, as its jar task builds it
    // (build/libs also keeps the jars of earlier versions). setScanSet, not scanSet.from(): only
    // the setter marks the set as configured, and the plugin scans its own defaults instead of
    // an unconfigured set.
    setScanSet(
        listOf(
            framework.resolve("lib"),
            repo.resolve("modules/docviewer/lib"),
            repo.resolve("modules/testrunner/lib"),
            repo.resolve("modules/testrunner/firephoque"),
            repo.resolve("gradle/wrapper/gradle-wrapper.jar"),
            framework.resolve("gradle-plugin/build/libs/gradle-plugin-$playVersion.jar"),
        ) + fileTree(framework) { include("play-*.jar") }.files
    )

    failBuildOnCVSS = 7.0f
    suppressionFile = framework.resolve("dependency-check-suppressions.xml").path
    // XML for the Jenkins Dependency-Check publisher of the nightly job.
    formats = listOf("HTML", "JSON", "XML")

    // The applications' database: on the Jenkins host the nightly Dependency_Check_NVD_Update
    // job keeps it current, so the day-old check below finds nothing to do there. Elsewhere
    // nothing else refreshes it, hence an update by default; -PnvdUpdate=false
    // (ant -Dnvd.update=false) scans whatever it holds, for when NVD is unreachable.
    data.directory = "${System.getProperty("user.home")}/.gradle/dependency-check-data"
    autoUpdate = providers.gradleProperty("nvdUpdate").map { it.toBoolean() }.getOrElse(true)
    nvd {
        apiKey = System.getenv("NVD_API_KEY") ?: ""
        validForHours = 24
    }

    analyzers {
        assemblyEnabled = false
        ossIndex {
            enabled = false
        }
    }
}

// The task's up-to-date check sees neither the contents of the scanned directories nor the NVD
// data: a jar dropped into framework/lib, or a CVE published since the last run, left it
// UP-TO-DATE, and the build passed without scanning.
tasks.named("dependencyCheckAnalyze") {
    outputs.upToDateWhen { false }
}
