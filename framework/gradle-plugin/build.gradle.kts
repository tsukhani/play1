plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
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
