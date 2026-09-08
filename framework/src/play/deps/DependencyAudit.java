package play.deps;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Audits {@code framework/lib/} for jars nothing needs.
 *
 * <p>Every jar in {@code lib/} is vendored and committed, and {@code dependencies.yml} sets
 * {@code transitiveDependencies: false}, so each one is there because a human listed it. Nothing
 * re-checks that the reason still holds: {@code org.eclipse.jdt.core} was added upstream in 2011,
 * became redundant in 2023 when {@code ecj} was listed beside it, and was still being re-resolved
 * and re-vendored on every JDT bump two years later. This target is the ratchet that stops the
 * next one lasting that long.
 *
 * <p><b>Why an allowlist rather than a bare "unused" report.</b> Static reachability alone is far
 * too blunt to act on: at the time of writing 40 of 136 jars are unreachable, and all but three
 * are demonstrably required — JNI natives nothing references, a driver named only in a config
 * string, ServiceLoader bindings, code reached only from a module's runtime-compiled {@code app/}
 * tree, and jars shipped purely for end-user applications to compile against. So an unreachable
 * jar is not a defect; an unreachable jar <em>nobody has justified</em> is. The allowlist entry is
 * the justification, and it lives next to the check so the next reader inherits the reasoning
 * instead of repeating the archaeology.
 *
 * <p>Findings, all reported together so one run shows the whole picture:
 * <ul>
 *   <li>{@code UNJUSTIFIED} — in {@code lib/}, unreachable, and not in the allowlist. Fails.
 *       Either it is dead and should leave {@code dependencies.yml}, or it is needed for a reason
 *       worth writing down.
 *   <li>{@code NO-CLASSES} — carries no {@code .class} entries at all, so it contributes no code
 *       to any classpath. Fails unless allowlisted; the Kotlin-multiplatform alias artifacts are
 *       the known, deliberate case.
 *   <li>{@code STALE-ENTRY} — an allowlist pattern matching no jar. Fails: this is how the list
 *       rots, and it means whoever dropped the dependency left its note behind.
 *   <li>{@code REDUNDANT-ENTRY} — an allowlist pattern whose jars are all statically reachable, so
 *       the entry no longer earns its place. Warns only; over-documentation is not a build break.
 * </ul>
 *
 * <p>Roots deliberately include the bundled modules' jars, not just {@code framework/classes}:
 * {@code jj-textile} and {@code jj-wikitext} are reached only from docviewer, and auditing the
 * framework alone would condemn them.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code framework.path} — absolute path to {@code framework/}. Required.
 *   <li>{@code audit.strict} — if {@code "false"}, report findings but exit 0. Default {@code true}.
 * </ul>
 */
public class DependencyAudit {

    /**
     * {@code unverified} is deliberate: a jar whose need nobody has actually confirmed is a real
     * state, and recording it beats either inventing a reason or leaving the entry out and failing
     * the build. Grep the allowlist for it to find the remaining work.
     */
    private static final Set<String> CATEGORIES = Set.of(
        "jni", "spi", "reflective", "app", "module", "kmp-alias", "tooling", "unverified");

    public static void main(String[] args) throws Exception {
        File frameworkPath = new File(required("framework.path"));
        boolean strict = !"false".equals(System.getProperty("audit.strict", "true"));

        File libDir = new File(frameworkPath, "lib");
        Set<String> libJars = jarNamesIn(libDir);
        if (libJars.isEmpty()) {
            throw new IllegalStateException("No jars found in " + libDir + " — run `ant resolve` first.");
        }

        List<File> roots = analysisRoots(frameworkPath);
        Set<String> reachable = reachableJars(frameworkPath, libDir, roots, libJars);
        List<Entry> allowlist = readAllowlist(new File(frameworkPath, "dependencies-audit.conf"));

        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int justified = 0;

        // UNJUSTIFIED / NO-CLASSES, per jar. A jar the framework can reach needs no justification,
        // so the allowlist is consulted only for the rest — otherwise an over-broad glob would
        // silently claim reachable jars too, inflating the "justified" count and hiding the
        // REDUNDANT-ENTRY signal that the glob is too wide.
        for (String jar : new TreeSet<>(libJars)) {
            Entry match = firstMatch(allowlist, jar);
            boolean reached = reachable.contains(jar);
            if (match != null) {
                match.matched.add(jar);
                if (reached) match.matchedReachable.add(jar);
            }
            if (reached) {
                continue;
            }
            if (match == null) {
                failures.add("UNJUSTIFIED    " + jar
                    + "\n                 unreachable from " + roots.size() + " analysis root(s) and not in"
                    + " dependencies-audit.conf."
                    + "\n                 Remove it from dependencies.yml, or add an entry saying what loads it.");
            }
            else {
                justified++;
                if (classCount(new File(libDir, jar)) == 0 && !"kmp-alias".equals(match.category)) {
                    failures.add("NO-CLASSES     " + jar
                        + "\n                 contains no .class entries, so it contributes no code to any"
                        + " classpath."
                        + "\n                 Allowlisted as '" + match.category + "'; only 'kmp-alias' expects"
                        + " an empty artifact.");
                }
            }
        }

        // STALE / REDUNDANT, per allowlist entry.
        for (Entry e : allowlist) {
            if (e.matched.isEmpty()) {
                failures.add("STALE-ENTRY    " + e.glob + "  (" + e.file + ":" + e.line + ")"
                    + "\n                 matches no jar in lib/. The dependency it documented is gone;"
                    + " delete the entry.");
            }
            else if (e.matched.equals(e.matchedReachable)) {
                warnings.add("REDUNDANT-ENTRY " + e.glob + "  (" + e.file + ":" + e.line + ")"
                    + " — every match is statically reachable; the entry is no longer needed.");
            }
        }

        System.out.println("~");
        System.out.println("~ Dependency audit: " + libJars.size() + " jars in lib/");
        System.out.println("~   " + reachable.size() + " statically reachable from " + roots.size() + " root(s)");
        System.out.println("~   " + justified + " justified by dependencies-audit.conf");
        System.out.println("~");

        warnings.forEach(w -> System.out.println("~ WARN  " + w));
        if (!warnings.isEmpty()) System.out.println("~");

        if (failures.isEmpty()) {
            System.out.println("~ Every jar in lib/ is accounted for.");
            System.out.println("~");
            return;
        }
        for (String f : failures) {
            System.out.println("~ " + f);
            System.out.println("~");
        }
        System.out.println("~ " + failures.size() + " unaccounted-for jar(s).");
        System.out.println("~");
        if (strict) {
            System.exit(1);
        }
    }

    /** framework/classes plus each bundled module's jar — see the class note on roots. */
    private static List<File> analysisRoots(File frameworkPath) {
        List<File> roots = new ArrayList<>();
        File classes = new File(frameworkPath, "classes");
        if (!classes.isDirectory()) {
            throw new IllegalStateException("Missing " + classes + " — run `ant compile` first.");
        }
        roots.add(classes);
        File modulesDir = new File(frameworkPath.getParentFile(), "modules");
        File[] modules = modulesDir.listFiles(File::isDirectory);
        if (modules != null) {
            Arrays.sort(modules);
            for (File m : modules) {
                File[] jars = new File(m, "lib").listFiles(
                    (d, n) -> n.startsWith("play-") && n.endsWith(".jar"));
                if (jars != null) {
                    Arrays.sort(jars);
                    roots.addAll(Arrays.asList(jars));
                }
            }
        }
        return roots;
    }

    /**
     * Runs {@code jdeps -summary -recursive} and walks the resulting jar-to-jar edges out from the
     * roots. Recursive matters: a jar reached only by another jar is still required, and reporting
     * it as unused would be worse than not running at all.
     */
    private static Set<String> reachableJars(File frameworkPath, File libDir, List<File> roots,
                                             Set<String> libJars) throws IOException, InterruptedException {
        String classpath = java.util.stream.Stream.concat(
                java.util.stream.Stream.of(new File(frameworkPath, "classes").getAbsolutePath()),
                libJars.stream().sorted().map(n -> new File(libDir, n).getAbsolutePath()))
            .collect(Collectors.joining(File.pathSeparator));

        List<String> cmd = new ArrayList<>(List.of(
            new File(System.getProperty("java.home"), "bin/jdeps").getAbsolutePath(),
            "--multi-release", "base", "-summary", "-recursive", "--class-path", classpath));
        roots.forEach(r -> cmd.add(r.getAbsolutePath()));

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();

        Map<String, Set<String>> edges = new HashMap<>();
        int parsed = 0;
        for (String line : out.split("\n")) {
            int arrow = line.indexOf("->");
            if (arrow < 0) continue;
            String from = baseName(line.substring(0, arrow).trim());
            String to = baseName(line.substring(arrow + 2).trim());
            if (!to.endsWith(".jar")) continue;          // JDK modules, and jdeps' "not found"
            edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
            parsed++;
        }

        // A jdeps that failed, or whose output format moved, must not be mistaken for "nothing is
        // used" — that would condemn all 136 jars at once. Refuse to report rather than mislead.
        Set<String> rootNames = roots.stream().map(r -> baseName(r.getAbsolutePath()))
            .collect(Collectors.toSet());
        boolean rootHasEdges = rootNames.stream().anyMatch(edges::containsKey);
        if (exit != 0 || parsed == 0 || !rootHasEdges) {
            throw new IllegalStateException(
                "jdeps produced no usable dependency edges (exit=" + exit + ", edges=" + parsed
                + ", roots-with-edges=" + rootHasEdges + "). Refusing to report an audit that would"
                + " call every jar unused.\n--- jdeps output (first 2000 chars) ---\n"
                + out.substring(0, Math.min(2000, out.length())));
        }

        Set<String> seen = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>(rootNames);
        while (!frontier.isEmpty()) {
            for (String next : edges.getOrDefault(frontier.pop(), Set.of())) {
                if (seen.add(next)) frontier.push(next);
            }
        }
        seen.retainAll(libJars);
        return seen;
    }

    private static List<Entry> readAllowlist(File f) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!f.isFile()) {
            throw new IllegalStateException("Missing allowlist " + f);
        }
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 3) {
                throw new IllegalStateException(f.getName() + ":" + (i + 1)
                    + " — expected `<glob> <category> <reason>`, got: " + line);
            }
            if (!CATEGORIES.contains(parts[1])) {
                throw new IllegalStateException(f.getName() + ":" + (i + 1) + " — unknown category '"
                    + parts[1] + "'. Known: " + new TreeSet<>(CATEGORIES));
            }
            entries.add(new Entry(parts[0], parts[1], parts[2], f.getName(), i + 1));
        }
        return entries;
    }

    private static Entry firstMatch(List<Entry> allowlist, String jar) {
        for (Entry e : allowlist) {
            if (e.matcher.matches(Path.of(jar))) return e;
        }
        return null;
    }

    private static int classCount(File jar) throws IOException {
        try (JarFile jf = new JarFile(jar)) {
            return (int) jf.stream().filter(e -> e.getName().endsWith(".class")).count();
        }
    }

    private static Set<String> jarNamesIn(File dir) {
        String[] names = dir.list((d, n) -> n.endsWith(".jar"));
        return names == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(names));
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf(File.separatorChar);
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String required(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("Missing required system property: " + key);
        }
        return v;
    }

    private static final class Entry {
        final String glob;
        final String category;
        final String reason;
        final String file;
        final int line;
        final PathMatcher matcher;
        final Set<String> matched = new TreeSet<>();
        final Set<String> matchedReachable = new TreeSet<>();

        Entry(String glob, String category, String reason, String file, int line) {
            this.glob = glob;
            this.category = category;
            this.reason = reason;
            this.file = file;
            this.line = line;
            this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        }
    }
}
