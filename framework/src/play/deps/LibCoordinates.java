package play.deps;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes {@code framework/lib-coordinates.properties}, the Maven coordinates of every jar in
 * {@code framework/lib/} (PF-192). The Gradle plugin reads it to keep an application's own copy of
 * a library the framework ships off the application's classpaths: to Gradle a jar in
 * {@code framework/lib} is a plain file, so it cannot tell that it is the same library as one the
 * application's dependencies resolve.
 *
 * <p>One line per jar, {@code <jar>=<group>:<artifact>:<version>[:<classifier>]}, from the
 * {@code require} entries of {@code dependencies.yml}. An entry's jar is
 * {@code <artifact>-<version>[-<classifier>].jar}, the name the {@code playCoreDependenciesLocal}
 * resolver looks up. A jar without coordinates has an empty value: the {@link
 * FrameworkResolve#VENDORED_JARS}, and an entry in the short form {@code <artifact> <version>},
 * which names no group. Ivy takes the artifact for the group there, and that is not always Maven
 * Central's ({@code jregex}), so no group is guessed.
 *
 * <p>Fails, listing every finding, on a jar in {@code lib/} that is neither an entry nor vendored,
 * and on an entry whose jar {@code lib/} does not have. Either means that {@code lib/} and
 * {@code dependencies.yml} disagree, which {@code ant resolve -Dprune=true} settles.
 *
 * <p>System properties: {@code framework.path}, the absolute path to {@code framework/}. Required.
 */
public class LibCoordinates {

    static final String FILE_NAME = "lib-coordinates.properties";

    // The two forms YamlParser reads: "<group> -> <artifact> <revision> [<classifier>]" and
    // "<artifact> <revision> [<classifier>]".
    private static final Pattern WITH_GROUP = Pattern.compile("(\\S+)\\s*->\\s*(\\S+)\\s+(\\S+)(?:\\s+(\\S+))?.*");
    private static final Pattern WITHOUT_GROUP = Pattern.compile("(\\S+)\\s+(\\S+)(?:\\s+(\\S+))?.*");

    public static void main(String[] args) throws IOException {
        File frameworkPath = new File(System.getProperty("framework.path"));
        List<String> problems = new ArrayList<>();
        Map<String, String> coordinates = coordinates(
            new File(frameworkPath, "dependencies.yml"), new File(frameworkPath, "lib"), problems);
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.err.println("~ " + problem));
            System.err.println("~ framework/lib and dependencies.yml disagree: run `ant resolve -Dprune=true`.");
            System.exit(1);
        }
        File out = new File(frameworkPath, FILE_NAME);
        Files.writeString(out.toPath(), render(coordinates), StandardCharsets.UTF_8);
        System.out.println("~ Wrote " + out + " (" + coordinates.size() + " jars)");
    }

    /**
     * The coordinates of each jar in {@code libDir} by jar name, empty for a jar without. Adds a
     * line to {@code problems} for each jar and each entry that have no counterpart.
     */
    static TreeMap<String, String> coordinates(File dependenciesYml, File libDir, List<String> problems) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        for (String dep : requireEntries(dependenciesYml)) {
            Matcher m = WITH_GROUP.matcher(dep);
            if (m.matches()) {
                String classifier = m.group(4);
                entries.put(jarName(m.group(2), m.group(3), classifier),
                    m.group(1) + ":" + m.group(2) + ":" + m.group(3) + (classifier == null ? "" : ":" + classifier));
            } else if ((m = WITHOUT_GROUP.matcher(dep)).matches()) {
                entries.put(jarName(m.group(1), m.group(2), m.group(3)), "");
            } else {
                problems.add("dependencies.yml: unknown dependency format: " + dep);
            }
        }

        Set<String> jars = new TreeSet<>();
        String[] names = libDir.list((dir, name) -> name.endsWith(".jar"));
        if (names != null) {
            jars.addAll(List.of(names));
        }
        TreeMap<String, String> coordinates = new TreeMap<>();
        for (String jar : jars) {
            if (entries.containsKey(jar)) {
                coordinates.put(jar, entries.get(jar));
            } else if (FrameworkResolve.VENDORED_JARS.contains(jar)) {
                coordinates.put(jar, "");
            } else {
                problems.add("framework/lib/" + jar + " matches no entry in dependencies.yml");
            }
        }
        for (String jar : entries.keySet()) {
            if (!jars.contains(jar)) {
                problems.add("dependencies.yml lists " + jar + ", which framework/lib does not have");
            }
        }
        return coordinates;
    }

    @SuppressWarnings("unchecked")
    private static List<String> requireEntries(File dependenciesYml) throws IOException {
        Map<String, Object> data;
        try (InputStream in = Files.newInputStream(dependenciesYml.toPath())) {
            data = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        List<String> entries = new ArrayList<>();
        for (Object dep : (List<Object>) data.get("require")) {
            // As YamlParser reads them: a string, or a map from one to its options.
            entries.add((dep instanceof Map<?, ?> map ? map.keySet().iterator().next() : dep).toString().trim());
        }
        return entries;
    }

    private static String jarName(String artifact, String revision, String classifier) {
        return artifact + "-" + revision + (classifier == null ? "" : "-" + classifier) + ".jar";
    }

    private static String render(Map<String, String> coordinates) {
        StringBuilder out = new StringBuilder()
            .append("# Maven coordinates of every jar in framework/lib (PF-192), written by `ant jar` from\n")
            .append("# dependencies.yml. <jar>=<group>:<artifact>:<version>[:<classifier>], and an empty value\n")
            .append("# for a jar without coordinates. The Gradle plugin reads this file to keep an application's\n")
            .append("# own copy of these libraries off its classpaths. Generated: do not edit.\n");
        coordinates.forEach((jar, coords) -> out.append(jar).append('=').append(coords).append('\n'));
        return out.toString();
    }
}
