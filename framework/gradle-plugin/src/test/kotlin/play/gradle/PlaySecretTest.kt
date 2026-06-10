package play.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Exercises the private generateSecret() via the playSecret TASK (its only
 * public surface). Asserts the exact output contract: a 64-char alphanumeric
 * secret written as <VARNAME>=<value> into certs/.env, where VARNAME is read
 * from application.secret=${'$'}{VARNAME} in conf/application.conf (default
 * PLAY_SECRET when conf is absent).
 */
class PlaySecretTest {

    // generateSecret() draws 64 chars from [A-Za-z0-9].
    private val secretRegex = Regex("^[A-Za-z0-9]{64}$")

    @Test
    fun `playSecret writes a 64-char secret to certs slash dot env under default var name`(@TempDir tmp: File) {
        TestProject.write(tmp)

        val result = TestProject.runner(tmp, "playSecret").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playSecret")?.outcome)

        val envFile = File(tmp, "certs/.env")
        assertTrue(envFile.isFile, "playSecret should create certs/.env")

        // No conf/application.conf in this project, so readSecretVarName falls
        // back to PLAY_SECRET.
        val line = envFile.readLines().single { it.startsWith("PLAY_SECRET=") }
        val value = line.substringAfter("PLAY_SECRET=")
        assertTrue(
            secretRegex.matches(value),
            "Secret value should be 64 alphanumeric chars but was '$value'"
        )

        // It also drops a certs/.env.example template referencing the var name.
        val example = File(tmp, "certs/.env.example")
        assertTrue(example.isFile, "playSecret should create certs/.env.example")
        assertTrue(
            example.readText().contains("PLAY_SECRET="),
            "certs/.env.example should reference the secret var name"
        )
    }

    @Test
    fun `playSecret honors the var name from application dot conf`(@TempDir tmp: File) {
        TestProject.write(tmp)
        File(tmp, "conf").mkdirs()
        // The framework rejects literal secrets and demands a ${'$'}{VAR}
        // placeholder; readSecretVarName parses the VAR out of it.
        File(tmp, "conf/application.conf").writeText(
            "application.secret=\${MYAPP_SECRET}\n"
        )

        val result = TestProject.runner(tmp, "playSecret").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":playSecret")?.outcome)

        val envFile = File(tmp, "certs/.env")
        val line = envFile.readLines().single { it.startsWith("MYAPP_SECRET=") }
        val value = line.substringAfter("MYAPP_SECRET=")
        assertTrue(
            secretRegex.matches(value),
            "Secret under the configured var name should be 64 alphanumeric chars but was '$value'"
        )
        // Mutation sanity: it must NOT fall back to the default name when conf
        // names a different variable.
        assertNull(
            envFile.readLines().firstOrNull { it.startsWith("PLAY_SECRET=") },
            "Should not emit the default PLAY_SECRET when conf names MYAPP_SECRET"
        )
    }

    @Test
    fun `playSecret rotates the value on each run`(@TempDir tmp: File) {
        TestProject.write(tmp)

        fun runAndReadSecret(): String {
            TestProject.runner(tmp, "playSecret").build()
            return File(tmp, "certs/.env").readLines()
                .single { it.startsWith("PLAY_SECRET=") }
                .substringAfter("PLAY_SECRET=")
        }

        val first = runAndReadSecret()
        val second = runAndReadSecret()
        assertNotNull(first)
        // Two SecureRandom draws of 64 chars colliding is astronomically
        // improbable; a constant/stubbed generator would fail this.
        assertNotEquals(first, second, "playSecret should generate a fresh secret each run")
        // And the rewrite must replace, not append a duplicate line.
        val playSecretLines = File(tmp, "certs/.env").readLines().count { it.startsWith("PLAY_SECRET=") }
        assertEquals(1, playSecretLines, "Re-running playSecret should replace, not duplicate, the line")
    }

    private fun assertNull(actual: Any?, message: String) =
        org.junit.jupiter.api.Assertions.assertNull(actual, message)
}
