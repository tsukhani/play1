package testrunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;

/**
 * Sorts unit tests into FirePhoque's two lanes for {@code /@tests.list}: a test that touches the
 * database runs on the single-permit lane ({@code D:}), any other on the parallel one ({@code U:}),
 * so that DB tests do not trample each other's {@code Fixtures.deleteDatabase()} or detach
 * in-flight entities.
 *
 * <p>A test touches the database when the classes its bytecode refers to, followed transitively
 * through application classes, include {@code play.test.Fixtures}, a class under {@code play.db},
 * an application entity (anything assignable to {@code play.db.jpa.Model}) or one of the
 * {@code testrunner.dbMarkers}. Framework, library and JDK classes end the walk: application code
 * reaches the database only through its own classes or through Play's database API, and the
 * markers catch both. A call through an interface also leads to every application class that
 * implements it (PF-154), because the static walk cannot see which one runs.
 *
 * <p>One instance serves one {@code /@tests.list} and remembers what it reads (PF-217): the
 * references of each class once, and the implementors of each interface once. Before, every
 * test's walk read again each class it reached, and each interface call it passed, on
 * {@code java.util.List} as much as on an application interface, ran
 * {@code getAssignableClasses}, which loads and tests every application class. On one application (882
 * unit tests, about 1,000 classes) that took 4 to 5 minutes at every autotest start, whatever the
 * run selected. Every test gets the lane it got before. Not thread-safe.
 *
 * <p>A class of its own in the module's {@code app/}, compiled at runtime with the controller, so
 * that the controller needs nothing from {@code play-testrunner.jar}: a start in test mode mounts
 * the module whether or not that jar is on its classpath. It depends only on the framework and
 * ASM, which lets the module's {@code unittest} target compile it beside its test.
 */
public final class DatabaseUseClassifier {

    /** What one class refers to, or why its bytecode could not be read. */
    private static final class References {
        static final References NONE = new References(List.of(), null);

        final List<String> names;
        final Exception failure;

        References(List<String> names, Exception failure) {
            this.names = names;
            this.failure = failure;
        }
    }

    private final Set<String> entityInternalNames;
    private final Set<String> markers;
    private final Function<String, byte[]> applicationBytecode;
    private final Function<String, List<String>> implementors;

    private final Map<String, References> references = new HashMap<>();
    private final Map<String, List<String>> implementorsByInterface = new HashMap<>();

    /**
     * @param entityInternalNames the application's entities, in internal form ({@code models/User})
     * @param markers             the {@code testrunner.dbMarkers}, in internal form: each matches
     *                            a class exactly or as a prefix
     * @param applicationBytecode the bytecode of an application class, by internal name; null for
     *                            a class that is not one, or has none
     * @param implementors        the application classes implementing an interface, by internal
     *                            name, in internal form
     */
    DatabaseUseClassifier(Set<String> entityInternalNames, Set<String> markers,
                          Function<String, byte[]> applicationBytecode,
                          Function<String, List<String>> implementors) {
        this.entityInternalNames = entityInternalNames;
        this.markers = markers;
        this.applicationBytecode = applicationBytecode;
        this.implementors = implementors;
    }

    /** A classifier over the running application's classes. */
    public static DatabaseUseClassifier forApplication() {
        return new DatabaseUseClassifier(entityInternalNames(), applicationDbMarkers(),
                DatabaseUseClassifier::applicationBytecode, DatabaseUseClassifier::applicationImplementors);
    }

    /** @return whether {@code testClass} belongs on the database lane */
    public boolean usesDatabase(Class<?> testClass) {
        // Apps that don't use JPA (e.g., Riak/Mongo/custom stores) leave
        // entityInternalNames empty, so the marker walk below has nothing to match
        // against and would mis-classify every test as pure-unit. Running those
        // through the parallel lane causes cross-test data races on the shared
        // application data store. Conservative default: route through the
        // single-permit DB lane. Apps with JPA entities still get the precise
        // per-test classification via the marker walk below.
        if (entityInternalNames.isEmpty() && markers.isEmpty()) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(testClass.getName().replace('.', '/'));
        while (!queue.isEmpty()) {
            String classInternal = queue.poll();
            if (!visited.add(classInternal)) continue;
            if (matchesDbMarker(classInternal)) {
                return true;
            }
            References read = references.computeIfAbsent(classInternal, this::read);
            if (read.failure != null) {
                Logger.warn(read.failure, "TestRunner: failed to scan %s during classification of %s, defaulting to DB-serial",
                        classInternal, testClass.getName());
                return true;
            }
            for (String name : read.names) {
                if (!visited.contains(name)) {
                    queue.add(name);
                }
            }
        }
        return false;
    }

    private boolean matchesDbMarker(String classInternal) {
        if (classInternal.equals("play/test/Fixtures")
                || classInternal.startsWith("play/db/")
                || entityInternalNames.contains(classInternal)) {
            return true;
        }
        for (String marker : markers) {
            if (classInternal.equals(marker) || classInternal.startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The classes {@code classInternal}'s methods call, read or instantiate, each followed by
     * the implementors of an interface it calls, in order and without repeats. Only application
     * classes are read: any other class ends the walk, so it refers to nothing.
     */
    private References read(String classInternal) {
        byte[] bytes = applicationBytecode.apply(classInternal);
        if (bytes == null) {
            return References.NONE;
        }
        Set<String> names = new LinkedHashSet<>();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private void add(String owner) {
                            if (owner != null) {
                                names.add(owner);
                            }
                        }
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String n,
                                                    String d, boolean isInterface) {
                            add(owner);
                            if (isInterface) {
                                names.addAll(implementorsOf(owner));
                            }
                        }
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String n, String d) {
                            add(owner);
                        }
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            add(type);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            return new References(List.of(), e);
        }
        return new References(List.copyOf(names), null);
    }

    private List<String> implementorsOf(String interfaceInternal) {
        return implementorsByInterface.computeIfAbsent(interfaceInternal, implementors);
    }

    private static byte[] applicationBytecode(String classInternal) {
        ApplicationClass appClass = Play.classes.getApplicationClass(classInternal.replace('/', '.'));
        if (appClass == null) {
            return null;
        }
        return appClass.enhancedByteCode != null ? appClass.enhancedByteCode : appClass.javaByteCode;
    }

    private static List<String> applicationImplementors(String interfaceInternal) {
        Class<?> iface;
        try {
            iface = Play.classloader.loadClass(interfaceInternal.replace('/', '.'));
        } catch (Throwable ignore) {
            return List.of(); // not a loadable interface
        }
        List<String> names = new ArrayList<>();
        for (ApplicationClass impl : Play.classes.getAssignableClasses(iface)) {
            names.add(impl.name.replace('.', '/'));
        }
        return names;
    }

    /**
     * Application-supplied DB markers, configured via the
     * {@code testrunner.dbMarkers} system property as a comma-separated list of
     * fully qualified class names or package prefixes. Each entry is matched
     * exactly (FQCN) or as a prefix (package). Use this on non-JPA apps to point
     * the classifier at your own persistence layer (e.g., a Riak {@code Persistence}
     * facade or an {@code AbstractPersistenceModel} base class) so tests that
     * touch real storage are routed through the serial DB lane.
     *
     * @return set of internal-form ({@code com/foo/Bar}) markers; empty if not configured
     */
    private static Set<String> applicationDbMarkers() {
        String prop = System.getProperty("testrunner.dbMarkers", "");
        if (prop.isBlank()) return Set.of();
        Set<String> set = new HashSet<>();
        for (String entry : prop.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed.replace('.', '/'));
            }
        }
        return set;
    }

    private static Set<String> entityInternalNames() {
        Set<String> set = new HashSet<>();
        for (ApplicationClass c : Play.classes.getAssignableClasses(play.db.jpa.Model.class)) {
            set.add(c.javaClass.getName().replace('.', '/'));
        }
        return set;
    }
}
