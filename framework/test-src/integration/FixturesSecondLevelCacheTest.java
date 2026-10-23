package integration;

import java.util.Collection;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.jpa.HibernateHints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import play.Play;
import play.db.jpa.JPA;
import play.libs.F;
import play.test.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-190 regression test: after {@link Fixtures#deleteDatabase()} Hibernate's second-level cache
 * returns none of the deleted rows.
 *
 * <p>{@code deleteDatabase()} deletes with plain SQL, which Hibernate never sees, and then calls
 * {@code afterFixtureLoad()}, where {@code JPAPlugin} used to clear only each entity manager. The
 * shared cache kept the rows, so finding a remembered id returned an entity whose row was gone
 * (JClaw's JF-24, where the insert that referenced it then failed on the foreign key). The plugin
 * now calls Hibernate's {@code evictAllRegions()}. JPA's {@code Cache.evictAll()} is not enough:
 * Hibernate's evicts entity data alone, and the collection and query cases below fail with it.
 *
 * <p>Rows are written with {@link Fixtures#executeSQL(String)}, as an application's SQL fixture
 * writes them, so Hibernate first sees them when a test loads them. The cache is the fixture
 * app's {@link MapRegionFactory}. {@code CachedShelf} is resolved through {@link Play#classloader}
 * because {@code ant compile-tests} also compiles the fixture app into {@code test-classes/}, and
 * Hibernate maps only Play's copy.
 */
@ExtendWith(IntegrationTestExtension.class)
public class FixturesSecondLevelCacheTest {

    private Class<?> shelf;
    private SessionFactory sessionFactory;

    @BeforeEach
    public void emptyDatabaseAndCache() throws Throwable {
        shelf = Play.classloader.loadClass("models.CachedShelf");
        sessionFactory = inTransaction(() -> JPA.em().getEntityManagerFactory().unwrap(SessionFactory.class));
        deleteDatabase();
        // Each test fills the cache itself, whatever an earlier one left in it.
        sessionFactory.getCache().evictAllRegions();
    }

    @Test
    public void aDeletedEntityIsNotFoundById() throws Throwable {
        executeSQL("insert into CachedShelf (id, name) values (1, 'fiction')");
        inTransaction(() -> JPA.em().find(shelf, 1L));
        assertTrue(sessionFactory.getCache().containsEntity(shelf, 1L),
                "precondition: the shelf is in the second-level cache");

        deleteDatabase();

        assertNull(inTransaction(() -> JPA.em().find(shelf, 1L)),
                "deleteDatabase() deleted the shelf, but the second-level cache still returned it");
    }

    @Test
    public void aCachedCollectionReturnsNoDeletedElements() throws Throwable {
        executeSQL("insert into CachedShelf (id, name) values (1, 'fiction');"
                + "insert into CachedBook (id, title, shelf_id) values (1, 'Emma', 1), (2, 'Persuasion', 1)");
        assertEquals(2, booksOnShelf1());
        assertTrue(sessionFactory.getCache().containsCollection(shelf.getName() + ".books", 1L),
                "precondition: the shelf's books are in the second-level cache");

        deleteDatabase();
        // A fixture that reuses the id: the shelf is back, its books are not.
        executeSQL("insert into CachedShelf (id, name) values (1, 'fiction')");

        assertEquals(0, booksOnShelf1(),
                "deleteDatabase() deleted the books, but the shelf's cached collection still listed them");
    }

    @Test
    public void aCacheableQueryReturnsNoDeletedRows() throws Throwable {
        executeSQL("insert into CachedBook (id, title) values (1, 'Emma'), (2, 'Persuasion')");
        assertEquals(List.of("Emma", "Persuasion"), titles());
        long hits = sessionFactory.getStatistics().getQueryCacheHitCount();
        titles();
        assertEquals(hits + 1, sessionFactory.getStatistics().getQueryCacheHitCount(),
                "precondition: the titles come from the query cache");

        deleteDatabase();

        assertEquals(List.of(), titles(),
                "deleteDatabase() deleted the books, but the query cache still returned their titles");
    }

    private int booksOnShelf1() throws Throwable {
        return inTransaction(() -> {
            Object shelf1 = JPA.em().find(shelf, 1L);
            return ((Collection<?>) shelf.getField("books").get(shelf1)).size();
        });
    }

    private static List<?> titles() throws Throwable {
        return inTransaction(() -> JPA.em().createQuery("select b.title from CachedBook b order by b.title")
                .setHint(HibernateHints.HINT_CACHEABLE, true)
                .getResultList());
    }

    private static void deleteDatabase() throws Throwable {
        inTransaction(() -> {
            Fixtures.deleteDatabase();
            return null;
        });
    }

    private static void executeSQL(String script) throws Throwable {
        inTransaction(() -> {
            Fixtures.executeSQL(script);
            return null;
        });
    }

    private static <T> T inTransaction(F.Function0<T> block) throws Throwable {
        return JPA.withTransaction(JPA.DEFAULT, false, block);
    }
}
