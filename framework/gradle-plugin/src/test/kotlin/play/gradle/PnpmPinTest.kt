package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * PF-207: an application's pnpm follows the framework's, which is the packageManager pin of
 * resources/nuxt-skel/package.json. These pin the arithmetic; PnpmSyncTaskTest runs the tasks.
 */
class PnpmPinTest {

    @Test
    fun `the plugin reports the pnpm pinned by the repo's skel`() {
        // The plugin reads the copy its jar carries, which processResources takes from the skel.
        // Reading the skel itself here fails the suite when that copy goes missing or stale.
        val skel = File("../../resources/nuxt-skel/package.json").readText()
        val pinned = Regex("\"packageManager\"\\s*:\\s*\"pnpm@([^\"+]+)").find(skel)!!.groupValues[1]

        assertEquals(pinned, frameworkPnpmVersion())
    }

    @Test
    fun `the version of a pin drops its hash and ignores other package managers`() {
        assertEquals("12.21.0", pnpmVersionOf("pnpm@12.21.0"))
        assertEquals("12.15.0", pnpmVersionOf("pnpm@12.15.0+sha512.b3951d6d"))
        assertNull(pnpmVersionOf("yarn@4.5.0"))
        assertNull(pnpmVersionOf(null))
    }

    @Test
    fun `a pin is rewritten in place and nothing else moves`() {
        val before = "{\n  \"name\": \"app-frontend\",\n  \"packageManager\" : \"pnpm@12.16.1+sha512.abc\",\n  \"private\": true\n}\n"

        assertEquals(
            "{\n  \"name\": \"app-frontend\",\n  \"packageManager\" : \"pnpm@12.21.0\",\n  \"private\": true\n}\n",
            withPnpmPin(before, "12.21.0"),
        )
    }

    @Test
    fun `a missing pin goes in first, indented and terminated like the next property`() {
        assertEquals(
            "{\n    \"packageManager\": \"pnpm@12.21.0\",\n    \"name\": \"app-frontend\"\n}\n",
            withPnpmPin("{\n    \"name\": \"app-frontend\"\n}\n", "12.21.0"),
        )
        // A CRLF checkout keeps CRLF on the line that goes in.
        assertEquals(
            "{\r\n  \"packageManager\": \"pnpm@12.21.0\",\r\n  \"name\": \"app-frontend\"\r\n}\r\n",
            withPnpmPin("{\r\n  \"name\": \"app-frontend\"\r\n}\r\n", "12.21.0"),
        )
    }
}
