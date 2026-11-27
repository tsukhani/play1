package play.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * PF-260: the forked test JVM's own word that it ended at the end of its run. Ant's {@code junitlauncher} judges a
 * forked JVM by its exit status and treats any status but 1, 2 and 3 as a success, so a JVM that a test ended with
 * {@code System.exit} or {@code Runtime.halt}, that crashed or that a signal killed passed the build, and the
 * classes after it never ran.
 *
 * <p>With {@value RunCompletion#PROPERTY} naming a file, this records {@code started}, {@code finished} or
 * {@code skipped} and the class in {@code <file>.progress} as each happens, and at the JVM's exit writes
 * {@code <file>} itself, but only when no test class is still running. A halt, a crash or a {@code SIGKILL} runs no
 * shutdown hook, and a {@code System.exit} or a {@code SIGTERM} during a test leaves its class running, so none of
 * them writes it. {@link RunCompletion}, run by the build afterwards, fails it when {@code <file>} is missing, and
 * also when a class the build selected never started, since a JVM ended between two classes finds none running.
 *
 * <p>Junitlauncher runs each test class as a test plan of its own, one after the other in one JVM, and JUnit makes
 * a new instance of this listener for each. So what the run has done is kept for the JVM, and a plan belongs to the
 * run when it starts while no class of the run is running. One that starts while a class runs is a launch a test
 * made of its own, and its instance stays out of it. Registered through {@code META-INF/services}, so it needs
 * nothing from Ant, and inert without the property.
 */
public class RunCompletionListener implements TestExecutionListener {

    private static final Object LOCK = new Object();

    /** The completion file of the run in this JVM; null until its first plan, and when there is no run. */
    private static Path file;

    /** Whether this JVM's run was looked for already: the first plan settles it. */
    private static boolean settled;

    /** The classes of the run that started and have not finished. */
    private static final Set<String> RUNNING = new LinkedHashSet<>();

    /** This instance's plan's classes, when that plan belongs to the run. */
    private final Map<TestIdentifier, String> classes = new HashMap<>();

    private boolean active;

    @Override
    public void testPlanExecutionStarted(TestPlan plan) {
        synchronized (LOCK) {
            if (!settled) {
                settled = true;
                file = claim();
            }
            if (file == null || !RUNNING.isEmpty()) {
                return;
            }
            active = true;
            for (TestIdentifier root : plan.getRoots()) {
                for (TestIdentifier child : plan.getChildren(root)) {
                    child.getSource().filter(ClassSource.class::isInstance)
                            .ifPresent(source -> classes.put(child, ((ClassSource) source).getClassName()));
                }
            }
        }
    }

    /** The completion file, with the shutdown hook that writes it; null when there is no run to record. */
    private static Path claim() {
        String name = System.getProperty(RunCompletion.PROPERTY);
        if (name == null || name.isBlank()) {
            return null;
        }
        Path completion = Path.of(name.trim()).toAbsolutePath();
        // Created, never taken over: a JVM a test started with the property passed on is not the run either.
        try {
            Files.writeString(RunCompletion.sibling(completion, ".progress"), "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (LOCK) {
                if (RUNNING.isEmpty()) {
                    write(completion, "ended after its last test class\n", false);
                }
            }
        }, "play-run-completion"));
        return completion;
    }

    @Override
    public void executionStarted(TestIdentifier identifier) {
        synchronized (LOCK) {
            String className = active ? classes.get(identifier) : null;
            if (className != null) {
                RUNNING.add(className);
                record("started", className);
            }
        }
    }

    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
        synchronized (LOCK) {
            String className = active ? classes.get(identifier) : null;
            if (className != null) {
                record("skipped", className);
            }
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        synchronized (LOCK) {
            String className = active ? classes.get(identifier) : null;
            if (className != null) {
                RUNNING.remove(className);
                record("finished", className);
            }
        }
    }

    private static void record(String event, String className) {
        write(RunCompletion.sibling(file, ".progress"), event + " " + className + "\n", true);
    }

    /** Written straight to the file, so a JVM that ends a moment later has left it whole. */
    private static void write(Path path, String text, boolean append) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
