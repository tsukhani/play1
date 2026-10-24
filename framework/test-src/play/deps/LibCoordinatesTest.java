package play.deps;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class LibCoordinatesTest {

    @Test
    void eachJarGetsTheCoordinatesOfItsEntry(@TempDir File dir) throws IOException {
        File yml = yml(dir,
            "    - com.squareup.okhttp3 -> okhttp-jvm 5.5.1",
            "    - io.netty -> netty-codec-native-quic 4.2.19.Final osx-aarch_64",
            "    - commons-io -> commons-io 2.22.0",
            "    - jregex 1.2_01");
        File lib = lib(dir, "okhttp-jvm-5.5.1.jar", "netty-codec-native-quic-4.2.19.Final-osx-aarch_64.jar",
            "commons-io-2.22.0.jar", "jregex-1.2_01.jar", "jj-textile.jar");
        List<String> problems = new ArrayList<>();

        Map<String, String> coordinates = LibCoordinates.coordinates(yml, lib, problems);

        assertThat(problems).isEmpty();
        assertThat(coordinates).containsExactly(
            entry("commons-io-2.22.0.jar", "commons-io:commons-io:2.22.0"),
            // Vendored, and an entry that names no group: neither has coordinates.
            entry("jj-textile.jar", ""),
            entry("jregex-1.2_01.jar", ""),
            entry("netty-codec-native-quic-4.2.19.Final-osx-aarch_64.jar", "io.netty:netty-codec-native-quic:4.2.19.Final:osx-aarch_64"),
            entry("okhttp-jvm-5.5.1.jar", "com.squareup.okhttp3:okhttp-jvm:5.5.1"));
    }

    @Test
    void aJarNoEntryNamesIsAProblem(@TempDir File dir) throws IOException {
        File yml = yml(dir, "    - com.squareup.okhttp3 -> okhttp-jvm 5.5.2");
        // What lib/ holds after a version bump in dependencies.yml that `ant resolve` has not seen.
        File lib = lib(dir, "okhttp-jvm-5.5.1.jar");
        List<String> problems = new ArrayList<>();

        Map<String, String> coordinates = LibCoordinates.coordinates(yml, lib, problems);

        assertThat(coordinates).isEmpty();
        assertThat(problems).containsExactly(
            "framework/lib/okhttp-jvm-5.5.1.jar matches no entry in dependencies.yml",
            "dependencies.yml lists okhttp-jvm-5.5.2.jar, which framework/lib does not have");
    }

    @Test
    void everyJarOfTheFrameworkHasCoordinatesButTheThreeThatHaveNone() throws IOException {
        // The real manifest and lib/, which `ant jar` also checks. The ant tests run in framework/.
        List<String> problems = new ArrayList<>();

        Map<String, String> coordinates = LibCoordinates.coordinates(new File("dependencies.yml"), new File("lib"), problems);

        assertThat(problems).isEmpty();
        assertThat(coordinates).containsKeys("jj-textile.jar", "jj-wikitext.jar", "jregex-1.2_01.jar");
        assertThat(coordinates).allSatisfy((jar, coords) -> {
            if (jar.startsWith("jj-") || jar.startsWith("jregex-")) {
                assertThat(coords).as(jar).isEmpty();
            } else {
                assertThat(coords).as(jar).matches("[^:]+:[^:]+:[^:]+(:[^:]+)?");
            }
        });
    }

    private static File yml(File dir, String... require) throws IOException {
        File yml = new File(dir, "dependencies.yml");
        Files.writeString(yml.toPath(), "self: play -> play $version\nrequire:\n" + String.join("\n", require) + "\n");
        return yml;
    }

    private static File lib(File dir, String... jars) throws IOException {
        File lib = new File(dir, "lib");
        lib.mkdirs();
        for (String jar : jars) {
            Files.createFile(new File(lib, jar).toPath());
        }
        return lib;
    }
}
