package play.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PF-260: the build's check, after a {@code junitlauncher} run, that its forked JVM ended at the end of its run
 * ({@link RunCompletionListener}). Run by the {@code run-completion-check} macro of {@code framework/run-completion.xml}
 * in a JVM of its own, where JUnit is not on the classpath: Ant 1.10.12 runs an unforked {@code <java>} under a
 * SecurityManager, which JDK 25 refuses. It prints what is wrong and exits with status 1, which fails the build.
 */
public final class RunCompletion {

    /** The system property the build names the completion file in, for {@link RunCompletionListener}. */
    public static final String PROPERTY = "play.tests.completion";

    private RunCompletion() {
    }

    /**
     * {@code <file><suffix>}, beside it. Here and not in the listener: this class runs where JUnit is not on the
     * classpath, so it must not load the listener.
     */
    static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    /**
     * Arguments: the completion file; a label for the suite, such as {@code ant unittest}; and the directory of the
     * test classes, against which {@code <file>.selected} lists the class files junitlauncher was given.
     */
    public static void main(String[] args) {
        String problem = problem(Path.of(args[0]), args[1], Path.of(args[2]));
        if (problem != null) {
            System.err.println(problem);
            System.exit(1);
        }
    }

    /** Null when the run ended at its end; otherwise what to tell the person who ran the build. */
    static String problem(Path file, String label, Path classesDir) {
        boolean ended = Files.exists(file);
        List<String> progress = lines(sibling(file, ".progress"));
        if (progress == null) {
            return label + ": the test JVM ended before its run began, so no test ran, or it did not load"
                    + " play.testsupport.RunCompletionListener, which records the run (test-classes on its classpath and"
                    + " -D" + PROPERTY + " naming " + file + ")."
                    + " Junitlauncher counts such a run as a success unless the JVM's exit status was 1, 2 or 3.";
        }
        Set<String> started = new LinkedHashSet<>(), reported = new LinkedHashSet<>();
        for (String line : progress) {
            int space = line.indexOf(' ');
            if (space > 0) {
                (line.startsWith("started ") ? started : reported).add(line.substring(space + 1));
            }
        }
        List<String> selected = new ArrayList<>();
        List<String> files = lines(sibling(file, ".selected"));
        for (String path : files == null ? List.<String>of() : files) {
            selected.add(className(classesDir, Path.of(path)));
        }
        List<String> running = new ArrayList<>(), unstarted = new ArrayList<>();
        for (String className : started) {
            if (!reported.contains(className)) {
                running.add(className);
            }
        }
        for (String className : selected) {
            if (!started.contains(className) && !reported.contains(className)) {
                unstarted.add(className);
            }
        }
        int total = Math.max(selected.size(), started.size());
        if (ended) {
            // The JVM ended with no class running: at the end of the run, or between two classes, which a stray
            // System.exit or a SIGTERM can do. Only the selected classes tell the two apart.
            if (unstarted.isEmpty()) {
                return null;
            }
            return label + ": " + unstarted.size() + " of " + total + " selected test classes never started: "
                    + String.join(", ", unstarted) + ". Either the test JVM ended between two classes, which"
                    + " junitlauncher counts as a success, or a class has no tests for JUnit to run on its own"
                    + " (a non-static @Nested class, say), which no *Test class may be.";
        }
        StringBuilder message = new StringBuilder(label).append(": the test JVM ended before its run finished");
        if (running.isEmpty() && unstarted.isEmpty()) {
            return message.append(", after all ").append(total)
                    .append(" test classes had reported. Junitlauncher counts that as a success.").toString();
        }
        message.append(", so ").append(running.size() + unstarted.size()).append(" of ").append(total)
                .append(" test classes did not report.");
        if (!running.isEmpty()) {
            message.append(" Running when it ended: ").append(String.join(", ", running)).append('.');
        }
        if (!unstarted.isEmpty()) {
            message.append(" Never started: ").append(String.join(", ", unstarted)).append('.');
        }
        return message.append(" Junitlauncher counts such a run as a success unless the JVM's exit status was 1, 2 or 3.")
                .toString();
    }

    /** {@code play/x/FooTest.class} under {@code classesDir} is {@code play.x.FooTest}. */
    static String className(Path classesDir, Path classFile) {
        String relative = classesDir.toAbsolutePath().normalize().relativize(classFile.toAbsolutePath().normalize()).toString();
        return relative.replaceAll("\\.class$", "").replace('\\', '.').replace('/', '.');
    }

    private static List<String> lines(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lines.add(line.strip());
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
