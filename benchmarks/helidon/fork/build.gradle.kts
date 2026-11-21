plugins {
    id("org.playframework.play1")
}

val framework = providers.gradleProperty("benchFramework").get()

play1 {
    frameworkPath.set(file(framework))
    frameworkVersion.set(file("$framework/framework/src/play/version").readText().trim())
}
