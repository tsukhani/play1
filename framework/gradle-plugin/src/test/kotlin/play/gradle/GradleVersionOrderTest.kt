package play.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PF-194: the version ordering and request matching behind the newer-request warning. */
class GradleVersionOrderTest {

    // Each list is in ascending order; the examples are those of Gradle's "Version ordering".
    @Test
    fun `orders versions as Gradle does`() {
        val ascending = listOf(
            listOf("1.1", "1.2"),
            listOf("1.a", "1.1"),
            listOf("1.A", "1.B", "1.a", "1.b"),
            listOf("1.1", "1.1.0"),
            listOf("1.1.a", "1.1"),
            listOf("1.0-dev", "1.0-ALPHA", "1.0-alpha", "1.0-rc"),
            listOf("1.0-zeta", "1.0-rc", "1.0-snapshot", "1.0-final", "1.0-ga", "1.0-release", "1.0-sp", "1.0"),
            listOf("4.2.9.Final", "4.2.19.Final", "4.2.19"),
            listOf("1.22.0", "1.22.1", "1.23.0"),
        )
        for (versions in ascending) {
            assertEquals(versions, versions.reversed().sortedWith(GradleVersionOrder), "ascending: $versions")
        }
        assertEquals(0, GradleVersionOrder.compare("1.0-RC-1", "1.0.rc.1"))
        assertEquals(0, GradleVersionOrder.compare("1a1", "1.a.1"))
    }

    @Test
    fun `a request is beyond the fork's version when higher or when its range leaves it out`() {
        assertTrue(asksBeyond("1.22.1", "1.22.0"))
        assertFalse(asksBeyond("1.22.0", "1.22.0"))
        assertFalse(asksBeyond("1.21", "1.22.0"))

        assertFalse(asksBeyond("[1.0,2.0)", "1.5"))
        assertFalse(asksBeyond("[1.5,2.0)", "1.5"))
        assertTrue(asksBeyond("(1.5,2.0)", "1.5"))
        assertTrue(asksBeyond("[2.0,3.0)", "1.5"))
        assertTrue(asksBeyond("[1.0,1.5)", "1.5"))
        assertFalse(asksBeyond("[1.0,1.5]", "1.5"))
        assertFalse(asksBeyond("[1.0,)", "9"))
        assertFalse(asksBeyond("(,2.0]", "1.5"))
        assertTrue(asksBeyond("]1.5,2.0[", "1.5"))
        assertFalse(asksBeyond("[1.5]", "1.5"))
        assertTrue(asksBeyond("[1.6]", "1.5"))

        assertFalse(asksBeyond("1.22.+", "1.22.0"))
        assertTrue(asksBeyond("1.23.+", "1.22.0"))
        assertFalse(asksBeyond("+", "1.22.0"))
        assertFalse(asksBeyond("latest.release", "1.22.0"))
        assertFalse(asksBeyond("", "1.22.0"))
    }
}
