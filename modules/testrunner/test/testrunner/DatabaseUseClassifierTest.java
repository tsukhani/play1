package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lane {@code /@tests.list} gives a unit test. The classifier is shown an "application" made
 * of this class's nested fixtures: their bytecode is read from the test classpath, any other class
 * counts as framework, library or JDK, and the implementors of each fixture interface are listed
 * by hand. Both lookups count their calls, which is how PF-217's regression is caught.
 */
public class DatabaseUseClassifierTest {

    private static final String APPLICATION = internal(DatabaseUseClassifierTest.class) + "$";

    private final Map<String, Integer> bytecodeReads = new HashMap<>();
    private final Map<String, Integer> implementorLookups = new HashMap<>();
    private final Map<String, byte[]> replacedBytecode = new HashMap<>();

    @Test
    public void aTestThatInstantiatesAnEntityUsesTheDatabase() {
        assertTrue(classifier().usesDatabase(UsesEntity.class));
    }

    @Test
    public void aTestThatCallsFixturesUsesTheDatabase() {
        assertTrue(classifier().usesDatabase(UsesFixtures.class));
    }

    @Test
    public void aClassNamedInTheDbMarkersCountsAsTheDatabase() {
        DatabaseUseClassifier classifier = new DatabaseUseClassifier(Set.of(), Set.of(internal(Marked.class)),
                this::bytecode, this::implementors);
        assertTrue(classifier.usesDatabase(UsesMarked.class));
        assertFalse(classifier.usesDatabase(Pure.class));
    }

    /** PF-154: nothing names EntityStore; only the call on the Store interface leads to it. */
    @Test
    public void anEntityReachedOnlyThroughAnInterfaceUsesTheDatabase() {
        assertTrue(classifier().usesDatabase(UsesStore.class));
    }

    @Test
    public void aTestThatReachesNoMarkerIsPure() {
        assertFalse(classifier().usesDatabase(Pure.class));
    }

    @Test
    public void withNeitherEntitiesNorMarkersEveryTestUsesTheDatabase() {
        DatabaseUseClassifier classifier = new DatabaseUseClassifier(Set.of(), Set.of(),
                this::bytecode, this::implementors);
        assertTrue(classifier.usesDatabase(Pure.class));
    }

    @Test
    public void aClassThatCannotBeReadSendsItsTestToTheDatabaseLane() {
        replacedBytecode.put(internal(Unreadable.class), new byte[] {0, 1, 2});
        DatabaseUseClassifier classifier = classifier();
        assertTrue(classifier.usesDatabase(UsesUnreadable.class));
        assertTrue(classifier.usesDatabase(UsesUnreadable.class), "a second walk meets the remembered failure");
    }

    /**
     * PF-217: every test's walk used to read each class it reached again, and to look up the
     * implementors of an interface at each call made on it, java.util.List's as much as an
     * application interface's. On one application that was 4 to 5 minutes per autotest start.
     */
    @Test
    public void oneListReadsEachClassOnceAndLooksUpEachInterfaceOnce() {
        DatabaseUseClassifier classifier = classifier();
        for (int round = 0; round < 2; round++) {
            assertFalse(classifier.usesDatabase(Pure.class));
            assertFalse(classifier.usesDatabase(AlsoPure.class));
            assertTrue(classifier.usesDatabase(UsesStore.class));
            assertTrue(classifier.usesDatabase(UsesEntity.class));
        }

        assertEquals(Map.of(), moreThanOnce(bytecodeReads), "classes read more than once");
        assertEquals(Map.of(), moreThanOnce(implementorLookups), "interfaces looked up more than once");
        // Not vacuous: both pure tests reach PlainGreeter, through two calls on Greeter each.
        assertEquals(1, bytecodeReads.get(internal(PlainGreeter.class)));
        assertEquals(1, implementorLookups.get(internal(Greeter.class)));
        assertEquals(1, implementorLookups.get("java/util/List"));
    }

    private DatabaseUseClassifier classifier() {
        return new DatabaseUseClassifier(Set.of(internal(Entity.class)), Set.of(),
                this::bytecode, this::implementors);
    }

    private byte[] bytecode(String classInternal) {
        bytecodeReads.merge(classInternal, 1, Integer::sum);
        if (!classInternal.startsWith(APPLICATION)) {
            return null;
        }
        if (replacedBytecode.containsKey(classInternal)) {
            return replacedBytecode.get(classInternal);
        }
        try (InputStream in = DatabaseUseClassifierTest.class.getResourceAsStream("/" + classInternal + ".class")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> implementors(String interfaceInternal) {
        implementorLookups.merge(interfaceInternal, 1, Integer::sum);
        if (interfaceInternal.equals(internal(Store.class))) {
            return List.of(internal(EntityStore.class));
        }
        if (interfaceInternal.equals(internal(Greeter.class))) {
            return List.of(internal(PlainGreeter.class));
        }
        return List.of();
    }

    private static Map<String, Integer> moreThanOnce(Map<String, Integer> counts) {
        return counts.entrySet().stream().filter(e -> e.getValue() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String internal(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    // The fixture application. None of these is named *Test, so the build does not run them.

    /** An entity, as far as {@link #classifier()} is told. */
    static class Entity {
    }

    static class UsesEntity {
        Object run() {
            return new Entity();
        }
    }

    static class UsesFixtures {
        void run() {
            play.test.Fixtures.deleteDatabase();
        }
    }

    static class Marked {
    }

    static class UsesMarked {
        Object run() {
            return new Marked();
        }
    }

    interface Store {
        void save();
    }

    static class EntityStore implements Store {
        @Override
        public void save() {
            new Entity();
        }
    }

    static class UsesStore {
        static Store store;

        void run() {
            store.save();
        }
    }

    interface Greeter {
        String greet(String name);
    }

    static class PlainGreeter implements Greeter {
        @Override
        public String greet(String name) {
            return name;
        }
    }

    static class Pure {
        static Greeter greeter;

        int run(List<String> names) {
            return names.size() + greeter.greet("a").length() + greeter.greet("b").length();
        }
    }

    static class AlsoPure {
        static Greeter greeter;

        int run(List<String> names) {
            return names.get(0).length() + greeter.greet("c").length() + greeter.greet("d").length();
        }
    }

    static class Unreadable {
    }

    static class UsesUnreadable {
        Object run() {
            return new Unreadable();
        }
    }
}
