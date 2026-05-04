package play.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

class Play1Plugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register<PlayInfoTask>("playInfo") {
            group = "play1"
            description = "Print Play 1 Gradle plugin info"
        }
    }
}

abstract class PlayInfoTask : DefaultTask() {
    @TaskAction
    fun run() {
        logger.lifecycle("Play 1 Gradle plugin loaded.")
    }
}
