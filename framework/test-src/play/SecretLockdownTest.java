package play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.exceptions.UnexpectedException;

/**
 * Verifies that application.secret is locked to a single declaration in
 * conf/application.conf using the form {@code application.secret=${VARNAME}}.
 * Different environments supply different values for the same variable, never
 * different declarations.
 */
public class SecretLockdownTest {

    private Path tempDir;
    private File originalAppPath;

    @BeforeEach
    public void setUp() throws IOException {
        new PlayBuilder().build();
        tempDir = Files.createTempDirectory("play-secret-test");
        originalAppPath = Play.applicationPath;
        Play.applicationPath = tempDir.toFile();
        Files.createDirectories(tempDir.resolve("conf"));
    }

    @AfterEach
    public void tearDown() throws IOException {
        Play.applicationPath = originalAppPath;
        Files.walk(tempDir)
             .sorted(java.util.Comparator.reverseOrder())
             .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    }

    @Test
    public void canonicalDeclarationPasses() throws IOException {
        writeMainConf("application.name=t\napplication.secret=${PLAY_SECRET}\n");
        Play.readConfiguration();
        // Unresolved at this layer (test env has no PLAY_SECRET); placeholder remains.
        assertThat(Play.configuration.getProperty("application.secret")).isEqualTo("${PLAY_SECRET}");
    }

    @Test
    public void canonicalWithSurroundingWhitespacePasses() throws IOException {
        writeMainConf("application.name=t\napplication.secret =  ${PLAY_SECRET}\n");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("application.secret")).isEqualTo("${PLAY_SECRET}");
    }

    @Test
    public void anyEnvVarNameAccepted() throws IOException {
        // Operator's choice — any external var name is fine.
        writeMainConf("application.name=t\napplication.secret=${MY_PROD_SECRET_42}\n");
        Play.readConfiguration();
        assertThat(Play.configuration.getProperty("application.secret")).isEqualTo("${MY_PROD_SECRET_42}");
    }

    @Test
    public void literalValueRejected() throws IOException {
        writeMainConf("application.name=t\napplication.secret=hardcoded-secret-value\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("must be a single environment-variable placeholder")
            .hasMessageContaining("hardcoded-secret-value");
    }

    @Test
    public void mixedLiteralAndPlaceholderRejected() throws IOException {
        writeMainConf("application.name=t\napplication.secret=prefix-${PLAY_SECRET}-suffix\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("must be a single environment-variable placeholder");
    }

    @Test
    public void defaultFallbackRejected() throws IOException {
        // ${VAR:default} would re-introduce a literal via the fallback.
        writeMainConf("application.name=t\napplication.secret=${PLAY_SECRET:fallback}\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("`${VAR:default}` fallback");
    }

    @Test
    public void frameworkInjectedNameRejected() throws IOException {
        writeMainConf("application.name=t\napplication.secret=${application.path}\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("framework-injected");
    }

    @Test
    public void frameworkIdOverrideRejected() throws IOException {
        // %xxx.application.secret is rejected: there is one secret per app, and
        // environment-specific variation comes from the env-var value, not from
        // a second declaration.
        writeMainConf(
            "application.name=t\n" +
            "application.secret=${PLAY_SECRET}\n" +
            "%test.application.secret=${TEST_SECRET}\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("cannot be overridden per framework id")
            .hasMessageContaining("%test.application.secret");
    }

    @Test
    public void frameworkIdOverrideWithLiteralAlsoRejected() throws IOException {
        writeMainConf(
            "application.name=t\n" +
            "application.secret=${PLAY_SECRET}\n" +
            "%prod.application.secret=hardcoded-prod-secret\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("cannot be overridden per framework id");
    }

    @Test
    public void missingDeclarationRejected() throws IOException {
        writeMainConf("application.name=t\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("application.secret is missing");
    }

    @Test
    public void declarationInIncludeRejected() throws IOException {
        writeMainConf(
            "application.name=t\n" +
            "application.secret=${PLAY_SECRET}\n" +
            "@include.extra=extra.conf\n");
        Files.writeString(tempDir.resolve("conf/extra.conf"),
            "application.secret=${OTHER_SECRET}\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("may only be declared in conf/application.conf")
            .hasMessageContaining("extra.conf");
    }

    @Test
    public void prefixedDeclarationInIncludeAlsoRejected() throws IOException {
        // %xxx. overrides aren't allowed anywhere — main conf or includes.
        writeMainConf(
            "application.name=t\n" +
            "application.secret=${PLAY_SECRET}\n" +
            "@include.extra=extra.conf\n");
        Files.writeString(tempDir.resolve("conf/extra.conf"),
            "%staging.application.secret=${STAGING_SECRET}\n");
        assertThatThrownBy(Play::readConfiguration)
            .isInstanceOf(UnexpectedException.class)
            .hasMessageContaining("cannot be overridden per framework id");
    }

    private void writeMainConf(String content) throws IOException {
        Files.writeString(tempDir.resolve("conf/application.conf"), content);
    }
}
