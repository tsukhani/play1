package play;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for environment variable interpolation in configuration,
 * including the ${VAR:default} syntax for default values.
 */
public class EnvVarConfigTest {

    private Path tempDir;
    private File originalAppPath;

    @BeforeEach
    public void setUp() throws IOException {
        new PlayBuilder().build();
        tempDir = Files.createTempDirectory("play-config-test");
        originalAppPath = Play.applicationPath;
        Play.applicationPath = tempDir.toFile();
        // Create conf directory
        Files.createDirectories(tempDir.resolve("conf"));
    }

    @AfterEach
    public void tearDown() throws IOException {
        Play.applicationPath = originalAppPath;
        // Clean up temp files
        Files.walk(tempDir)
             .sorted(java.util.Comparator.reverseOrder())
             .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    }

    @Test
    public void defaultValueUsedWhenEnvVarMissing() throws IOException {
        writeConfig("test.key=${PLAY_TEST_NONEXISTENT_VAR_12345:fallback_value}");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("test.key")).isEqualTo("fallback_value");
    }

    @Test
    public void defaultValueEmptyString() throws IOException {
        writeConfig("test.key=${PLAY_TEST_NONEXISTENT_VAR_12345:}");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("test.key")).isEqualTo("");
    }

    @Test
    public void defaultValueWithSpecialChars() throws IOException {
        writeConfig("test.url=${PLAY_TEST_NONEXISTENT_VAR_12345:jdbc:postgresql://localhost:5432/mydb}");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("test.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/mydb");
    }

    @Test
    public void envVarFoundOverridesDefault() throws IOException {
        // PATH is always set on any system
        writeConfig("test.key=${PATH:should_not_use_this}");
        Play.readConfiguration();
        String pathValue = System.getenv("PATH");
        if (pathValue == null) {
            pathValue = System.getProperty("PATH");
        }
        assertThat(Play.configuration.getProperty("test.key")).isNotEqualTo("should_not_use_this");
        assertThat(Play.configuration.getProperty("test.key")).isEqualTo(pathValue);
    }

    @Test
    public void systemPropertyResolvesBeforeEnvVar() throws IOException {
        String uniqueKey = "play.test.sysprop." + System.nanoTime();
        System.setProperty(uniqueKey, "from_sysprop");
        try {
            writeConfig("test.key=${" + uniqueKey + ":default_val}");
            Play.readConfiguration();
            assertThat(Play.configuration.getProperty("test.key")).isEqualTo("from_sysprop");
        } finally {
            System.clearProperty(uniqueKey);
        }
    }

    @Test
    public void noDefaultAndNoValueLeavesPlaceholder() throws IOException {
        writeConfig("test.key=prefix-${PLAY_TEST_NONEXISTENT_VAR_12345}-suffix");
        Play.readConfiguration();
        // When no default and no env var, the unresolved placeholder remains in the value
        assertThat(Play.configuration.getProperty("test.key"))
                .isEqualTo("prefix-${PLAY_TEST_NONEXISTENT_VAR_12345}-suffix");
    }

    @Test
    public void multiplePlaceholdersInOneValue() throws IOException {
        writeConfig("test.key=${PLAY_TEST_NONEXISTENT_A:alpha}-${PLAY_TEST_NONEXISTENT_B:beta}");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("test.key")).isEqualTo("alpha-beta");
    }

    @Test
    public void applicationPathSpecialVariable() throws IOException {
        writeConfig("test.key=${application.path}/data");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("test.key"))
                .isEqualTo(Play.applicationPath.getAbsolutePath() + "/data");
    }

    @Test
    public void noPlaceholderUnchanged() throws IOException {
        writeConfig("test.key=plain_value");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("test.key")).isEqualTo("plain_value");
    }

    private void writeConfig(String content) throws IOException {
        Files.writeString(tempDir.resolve("conf/application.conf"),
                "application.name=test\n" + content + "\n");
    }
}
