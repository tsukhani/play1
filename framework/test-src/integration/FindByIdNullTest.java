package integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import play.Play;
import play.db.jpa.JPA;
import play.libs.F;
import play.test.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-198: the {@code findById} that {@code JPAEnhancer} generates on every model returns null for a
 * null id, as {@code JPAModelLoader.findById} does, instead of throwing Hibernate's
 * "Identifier may not be null". An id of the wrong type is a programming error and still throws.
 *
 * <p>{@code Note} is resolved through {@link Play#classloader}: Hibernate maps only Play's copy of
 * the fixture app's classes, and the enhanced {@code findById} exists only on that copy.
 */
@ExtendWith(IntegrationTestExtension.class)
public class FindByIdNullTest {

    private static Object findById(Class<?> model, Object id) throws Throwable {
        return inTransaction(() -> model.getMethod("findById", Object.class).invoke(null, id));
    }

    @Test
    public void aNullIdReturnsNull() throws Throwable {
        Class<?> note = Play.classloader.loadClass("models.Note");
        assertNull(findById(note, null));
    }

    @Test
    public void anExistingIdReturnsTheEntityAndAnUnknownIdReturnsNull() throws Throwable {
        Class<?> note = Play.classloader.loadClass("models.Note");
        inTransaction(() -> {
            Fixtures.deleteDatabase();
            Fixtures.executeSQL("insert into Note (id, text) values (1, 'hello')");
            return null;
        });

        Object found = findById(note, 1L);
        assertNotNull(found);
        assertEquals("hello", note.getField("text").get(found));
        assertNull(findById(note, 999L));
    }

    @Test
    public void anIdOfTheWrongTypeStillThrows() throws Throwable {
        Class<?> note = Play.classloader.loadClass("models.Note");
        Throwable thrown = assertThrows(Throwable.class, () -> findById(note, "not-a-number"));
        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(root instanceof IllegalArgumentException, root.toString());
    }

    private static <T> T inTransaction(F.Function0<T> block) throws Throwable {
        return JPA.withTransaction(JPA.DEFAULT, false, block);
    }
}
