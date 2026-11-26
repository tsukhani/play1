package play.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** PF-258: aot.training.paths, as playAotGen reads it; bundle-play.sh's aot_training_paths reads it alike. */
class TrainingPathsTest {

    @Test
    fun `without the key the training run requests slash, as before`() {
        assertEquals(listOf("/"), trainingPaths(null))
        assertEquals(listOf("/"), trainingPaths(""))
        assertEquals(listOf("/"), trainingPaths(" , ,"))
    }

    @Test
    fun `the paths come trimmed, in order, empty items left out`() {
        assertEquals(listOf("/", "/products", "/api/status?full=1"), trainingPaths(" /, /products ,,/api/status?full=1 "))
    }

    @Test
    fun `a path that does not start with a slash or holds whitespace is refused`() {
        val notAPath = assertThrows<GradleException> { trainingPaths("/ok, products") }
        assertTrue("'products' is not a path" in notAPath.message!!, notAPath.message)
        val whitespace = assertThrows<GradleException> { trainingPaths("/a b") }
        assertTrue("'/a b' holds whitespace" in whitespace.message!!, whitespace.message)
    }
}
