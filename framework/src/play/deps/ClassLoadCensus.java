package play.deps;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import play.deps.DependencyAudit.Entry;

/**
 * Reports which jars in {@code framework/lib/} actually supplied a loaded class while the test
 * suites ran — the runtime half of the dependency audit.
 *
 * <p>{@link DependencyAudit} answers "can the framework's bytecode reach this jar", which is
 * necessary but blunt: 40 of 136 jars are unreachable and nearly all are still required, because
 * static analysis cannot see JNI natives, classes named only in a config string, ServiceLoader
 * bindings, or a module's runtime-compiled {@code app/} tree. This pass answers the complementary
 * question — "was it ever actually loaded" — by parsing the JVM's own
 * {@code -Xlog:class+load} output, which records the source jar of every class the VM defines,
 * whatever contrived route got it there.
 *
 * <p><b>What a "never loaded" verdict does and does not mean.</b> It means the suites did not
 * exercise it, not that it is dead. The suites drive H2 rather than PostgreSQL, and never run an
 * end-user application's tests, so {@code postgresql} and {@code junit-jupiter-engine} will show
 * zero loads and must still ship. This is why the census only ever reports and never fails a
 * build: acting on it requires knowing what the run covered. Where it earns its keep is the
 * opposite direction — a jar the census <em>does</em> load is proven necessary, which is exactly
 * what settles the {@code unverified} entries in {@code dependencies-audit.conf}, since the
 * integration suite boots Hibernate against H2 with {@code jpa.ddl=create-drop}.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code framework.path} — absolute path to {@code framework/}. Required.
 *   <li>{@code census.dir} — directory of {@code -Xlog:class+load} files. Required.
 * </ul>
 */
public class ClassLoadCensus {

    public static void main(String[] args) throws Exception {
        File frameworkPath = new File(System.getProperty("framework.path", ""));
        File censusDir = new File(System.getProperty("census.dir", ""));
        if (!frameworkPath.isDirectory() || !censusDir.isDirectory()) {
            throw new IllegalArgumentException(
                "framework.path and census.dir must both name existing directories");
        }

        File[] logs = censusDir.listFiles((d, n) -> n.endsWith(".log"));
        if (logs == null || logs.length == 0) {
            throw new IllegalStateException("No *.log files in " + censusDir
                + " — run `ant audit-census`, which drives the suites with -Xlog:class+load.");
        }

        Set<String> libJars = DependencyAudit.jarNamesIn(new File(frameworkPath, "lib"));
        Map<String, Integer> loadsPerJar = new TreeMap<>();
        long totalClasses = 0;

        for (File log : logs) {
            for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
                int src = line.indexOf(" source: ");
                if (src < 0) continue;
                totalClasses++;
                String jar = jarNameFrom(line.substring(src + " source: ".length()).trim());
                if (jar != null && libJars.contains(jar)) {
                    loadsPerJar.merge(jar, 1, Integer::sum);
                }
            }
        }

        // The same refusal-to-mislead guard the static audit uses: a parse that silently yields
        // nothing would report every jar as never-loaded, which is the most damaging output this
        // tool could produce.
        if (totalClasses == 0) {
            throw new IllegalStateException("Parsed " + logs.length + " log file(s) but found no"
                + " 'source:' lines. The -Xlog:class+load format may have changed; refusing to"
                + " report that nothing was loaded.");
        }

        List<Entry> allowlist = DependencyAudit.readAllowlist(
            new File(frameworkPath, "dependencies-audit.conf"));

        Map<String, List<String>> neverLoadedByCategory = new LinkedHashMap<>();
        List<String> unverifiedLoaded = new ArrayList<>();
        List<String> unverifiedNotLoaded = new ArrayList<>();

        for (String jar : new TreeSet<>(libJars)) {
            Entry e = firstMatch(allowlist, jar);
            String category = e == null ? "(reachable / unlisted)" : e.category;
            int loads = loadsPerJar.getOrDefault(jar, 0);
            if (loads == 0) {
                neverLoadedByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(jar);
            }
            if (e != null && "unverified".equals(e.category)) {
                (loads > 0 ? unverifiedLoaded : unverifiedNotLoaded)
                    .add(jar + (loads > 0 ? "  (" + loads + " classes loaded)" : ""));
            }
        }

        System.out.println("~");
        System.out.println("~ Class-load census over " + logs.length + " JVM log(s)");
        System.out.println("~   " + totalClasses + " class loads observed");
        System.out.println("~   " + loadsPerJar.size() + " of " + libJars.size()
            + " jars in lib/ supplied at least one class");
        System.out.println("~");

        System.out.println("~ Verdict on 'unverified' allowlist entries:");
        if (unverifiedLoaded.isEmpty() && unverifiedNotLoaded.isEmpty()) {
            System.out.println("~   none left — dependencies-audit.conf has no unverified entries.");
        }
        for (String j : unverifiedLoaded) {
            System.out.println("~   CONFIRMED NEEDED  " + j);
        }
        for (String j : unverifiedNotLoaded) {
            System.out.println("~   STILL UNPROVEN    " + j
                + "  (not loaded by this run; removal still needs a judgement about"
                + " what the suites do not cover)");
        }
        System.out.println("~");

        System.out.println("~ Never loaded during this run, by allowlist category:");
        for (Map.Entry<String, List<String>> byCat : neverLoadedByCategory.entrySet()) {
            System.out.println("~   [" + byCat.getKey() + "]");
            for (String jar : byCat.getValue()) {
                System.out.println("~       " + jar);
            }
        }
        System.out.println("~");
        System.out.println("~ Reminder: 'never loaded' means these suites did not exercise it — they"
            + " use H2, not PostgreSQL,");
        System.out.println("~ and never run an end-user application's tests. This report never fails"
            + " the build.");
        System.out.println("~");
    }

    /**
     * Pulls the jar filename out of a {@code -Xlog:class+load} source field. The JVM writes
     * {@code file:/path/to/foo.jar} for jars, and things like {@code shared objects file},
     * {@code __JVM_DefineClass__} or a bare directory for everything else — all of which this
     * returns null for.
     */
    private static String jarNameFrom(String source) {
        if (!source.endsWith(".jar")) return null;
        int slash = source.lastIndexOf('/');
        return slash < 0 ? null : source.substring(slash + 1);
    }

    private static Entry firstMatch(List<Entry> allowlist, String jar) {
        for (Entry e : allowlist) {
            if (e.matcher.matches(java.nio.file.Path.of(jar))) return e;
        }
        return null;
    }
}
