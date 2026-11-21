// The framework under test is the benchFramework property, which run.py writes into the gradle.properties of its copy.
pluginManagement {
    val framework = providers.gradleProperty("benchFramework").get()
    val version = java.io.File("$framework/framework/src/play/version").readText().trim()
    repositories {
        maven { url = uri(java.io.File("$framework/framework/gradle-plugin-repo")) }
        gradlePluginPortal()
    }
    plugins {
        id("org.playframework.play1") version version
    }
}
rootProject.name = "bench-fork"
