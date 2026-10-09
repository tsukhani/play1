package play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.exceptions.ConfigurationException;
import play.plugins.ConfigurablePluginDisablingPlugin;
import play.plugins.PluginCollection;

/**
 * Declared configuration requirements (PF-179): what counts as a violation, how the
 * violations are reported, and when the registry is emptied.
 *
 * <p>Cases that only concern the registry set {@code Play.configuration} directly. Cases
 * that depend on how a value got there — an unresolved placeholder, a profile prefix, a
 * second read — go through a real {@code conf/application.conf} and
 * {@link Play#readConfiguration()}, as {@link EnvVarConfigTest} does.
 */
public class ConfigRequirementsTest {

    /** Never defined, so a placeholder naming it stays unresolved. */
    private static final String UNSET = "PLAY_TEST_NONEXISTENT_VAR_12345";
    private static final String PLAY_SECRET_KEY = "PLAY_SECRET";

    private Path tempDir;
    private File savedAppPath;
    private Properties savedConfiguration;
    private String savedId;
    private PluginCollection savedPluginCollection;
    private List<PlayPlugin> savedPlugins;
    private String savedPlaySecret;

    @BeforeEach
    public void setUp() throws IOException {
        savedConfiguration = Play.configuration;
        savedId = Play.id;
        savedPluginCollection = Play.pluginCollection;
        savedPlugins = Play.plugins;
        new PlayBuilder().build();
        savedAppPath = Play.applicationPath;
        tempDir = Files.createTempDirectory("play-config-requirements-test");
        Play.applicationPath = tempDir.toFile();
        Files.createDirectories(tempDir.resolve("conf"));
        Play.id = "test";
        // The unit suite shares one JVM and several classes leave their own collection in
        // Play.pluginCollection; every read here would broadcast to whatever was left.
        Play.pluginCollection = new PluginCollection();
        // writeConf's application.secret=${PLAY_SECRET} has to resolve for Play.start().
        savedPlaySecret = System.getProperty(PLAY_SECRET_KEY);
        System.setProperty(PLAY_SECRET_KEY, "configrequirementstest_secret_placeholder");
        ConfigRequirements.reset();
    }

    @AfterEach
    public void tearDown() throws IOException {
        ConfigRequirements.reset();
        if (savedPlaySecret == null) {
            System.clearProperty(PLAY_SECRET_KEY);
        } else {
            System.setProperty(PLAY_SECRET_KEY, savedPlaySecret);
        }
        Play.pluginCollection = savedPluginCollection;
        Play.plugins = savedPlugins;
        Play.id = savedId;
        Play.applicationPath = savedAppPath;
        Play.configuration = savedConfiguration;
        Files.walk(tempDir)
             .sorted(java.util.Comparator.reverseOrder())
             .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    }

    // --- require ---

    @Test
    public void requirePassesWhenTheKeyHasAValue() {
        Play.configuration.setProperty("billing.apiKey", "sk_test_123");
        ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void requireFailsWhenTheKeyIsAbsent() {
        ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("billing.apiKey (declared by " + BillingPlugin.class.getName() + ") is missing. "
                + "Set it in conf/application.conf.");
    }

    @Test
    public void requireFailsWhenTheKeyIsEmptyOrBlank() {
        ConfigRequirements.require("billing", "billing.apiKey");
        for (String empty : new String[] {"", "   "}) {
            Play.configuration.setProperty("billing.apiKey", empty);
            assertThatThrownBy(ConfigRequirements::check)
                .as("value '%s'", empty)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("billing.apiKey (declared by billing) is missing.");
        }
    }

    // --- requireIn ---

    @Test
    public void requireInPassesForAnAllowedValueInAnyLetterCase() {
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        for (String allowed : new String[] {"live", "sandbox", "LIVE", "Sandbox"}) {
            Play.configuration.setProperty("billing.mode", allowed);
            assertThatCode(ConfigRequirements::check).as(allowed).doesNotThrowAnyException();
        }
    }

    @Test
    public void requireInIgnoresWhitespaceAroundTheValue() {
        // Play trims what it reads from the file, but a value substituted for a placeholder
        // arrives as the variable holds it, trailing newline included.
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        Play.configuration.setProperty("billing.mode", " Live\n");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void requireInPassesWhenTheKeyIsAbsentOrEmpty() {
        // The allowed set constrains a value; it does not demand one. application.log.format
        // is declared this way and existing applications leave it out.
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
        Play.configuration.setProperty("billing.mode", "");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void requireInFailsForAValueOutsideTheSet() {
        Play.configuration.setProperty("billing.mode", "staging");
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("billing.mode (declared by " + BillingPlugin.class.getName() + ") is \"staging\", "
                + "which is not an allowed value. Set it to one of: live, sandbox.");
    }

    @Test
    public void aKeyMayBeDeclaredWithBoth() {
        ConfigRequirements.require(BillingPlugin.class, "billing.mode");
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");

        // Absent: require is what fails, and the fix names the values to choose from.
        assertThatThrownBy(ConfigRequirements::check)
            .hasMessageContaining("(1 problem)")
            .hasMessageContaining("is missing. Set it in conf/application.conf to one of: live, sandbox.");

        Play.configuration.setProperty("billing.mode", "staging");
        assertThatThrownBy(ConfigRequirements::check)
            .hasMessageContaining("(1 problem)")
            .hasMessageContaining("is \"staging\", which is not an allowed value.");

        Play.configuration.setProperty("billing.mode", "live");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void aKeyDeclaredByTwoOwnersIsReportedOnceNamingBoth() {
        ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        ConfigRequirements.require("invoicing", "billing.apiKey");
        assertThatThrownBy(ConfigRequirements::check)
            .hasMessageContaining("(1 problem)")
            .hasMessageContaining("billing.apiKey (declared by " + BillingPlugin.class.getName() + ", invoicing) is missing.");
    }

    @Test
    public void aValueHasToSatisfyEveryAllowedSetDeclaredForItsKey() {
        ConfigRequirements.requireIn("billing", "billing.mode", "live", "sandbox");
        ConfigRequirements.requireIn("invoicing", "billing.mode", "sandbox", "offline");
        Play.configuration.setProperty("billing.mode", "live");
        assertThatThrownBy(ConfigRequirements::check)
            .hasMessageContaining("billing.mode (declared by billing, invoicing) is \"live\", which is not an "
                + "allowed value. Set it to one of: sandbox.");
        Play.configuration.setProperty("billing.mode", "sandbox");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    // --- unresolved placeholders ---

    @Test
    public void unresolvedPlaceholderOnARequiredKeyIsReportedAsMissing() throws IOException {
        writeConf("billing.apiKey=${" + UNSET + "}");
        Play.readConfiguration();
        ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("billing.apiKey (declared by " + BillingPlugin.class.getName() + ") is missing: "
                + "nothing defines ${" + UNSET + "}. Define " + UNSET + " in the environment");
    }

    @Test
    public void unresolvedPlaceholderOnARequireInKeyIsReportedAsMissing() throws IOException {
        // Left alone this would be compared with the allowed values and reported as a bad
        // value, sending the operator to the wrong fix.
        writeConf("billing.mode=${" + UNSET + "}");
        Play.readConfiguration();
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("is missing: nothing defines ${" + UNSET + "}.")
            .hasMessageContaining("Allowed values: live, sandbox.");
    }

    @Test
    public void unresolvedPlaceholderInsideALongerValueIsReportedAsMissing() throws IOException {
        writeConf("billing.url=https://user:hunter2@${" + UNSET + "}/v1");
        Play.readConfiguration();
        ConfigRequirements.require("billing", "billing.url");
        assertThatThrownBy(ConfigRequirements::check)
            .hasMessageContaining("billing.url (declared by billing) is missing: nothing defines ${" + UNSET + "}.")
            // Only the variable is named; the rest of the value may be a credential.
            .hasMessageNotContaining("hunter2");
    }

    @Test
    public void placeholderWithADefaultSatisfiesTheDeclaration() throws IOException {
        writeConf("billing.mode=${" + UNSET + ":sandbox}");
        Play.readConfiguration();
        ConfigRequirements.require("billing", "billing.mode");
        ConfigRequirements.requireIn("billing", "billing.mode", "live", "sandbox");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void undeclaredKeysAreNotLookedAt() throws IOException {
        writeConf("db.url=${" + UNSET + "}\nmail.smtp.host=\nsome.mode=whatever");
        Play.readConfiguration();
        // Exactly what EnvVarConfigTest.noDefaultAndNoValueLeavesPlaceholder pins, and no
        // complaint about it at start.
        assertThat(Play.configuration.getProperty("db.url")).isEqualTo("${" + UNSET + "}");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    // --- reporting ---

    @Test
    public void everyViolationIsReportedInOneException() {
        Play.configuration.setProperty("billing.mode", "staging");
        Play.configuration.setProperty("billing.region", "${" + UNSET + "}");
        Play.configuration.setProperty("billing.currency", "EUR");
        ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        ConfigRequirements.require("region lookup", "billing.region");
        ConfigRequirements.require(BillingPlugin.class, "billing.currency");
        String plugin = BillingPlugin.class.getName();
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessage("Invalid configuration (3 problems):\n"
                + "  1. billing.apiKey (declared by " + plugin + ") is missing. "
                + "Set it in conf/application.conf.\n"
                + "  2. billing.mode (declared by " + plugin + ") is \"staging\", which is not an "
                + "allowed value. Set it to one of: live, sandbox.\n"
                + "  3. billing.region (declared by region lookup) is missing: nothing defines "
                + "${" + UNSET + "}. Define " + UNSET + " in the environment, in certs/.env or as a "
                + "-D system property, or give the placeholder a default: ${" + UNSET + ":value}.");
    }

    // --- profiles ---

    @Test
    public void inactiveProfileLinesAreIgnored() throws IOException {
        // Play.id is "test": the %prod. lines are dropped before placeholders are resolved,
        // so neither their unresolved variable nor their bad value is ever seen.
        writeConf(
            "billing.apiKey=sk_test_123\n" +
            "%prod.billing.apiKey=${" + UNSET + "}\n" +
            "billing.mode=sandbox\n" +
            "%prod.billing.mode=staging\n" +
            "%prod.application.log.format=yaml\n");
        Play.readConfiguration();
        ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void aKeySetOnlyForAnotherProfileIsMissing() throws IOException {
        writeConf("%prod.billing.apiKey=sk_live_123");
        Play.readConfiguration();
        ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("billing.apiKey (declared by " + BillingPlugin.class.getName() + ") is missing.");
    }

    @Test
    public void theActiveProfileLineIsTheOneChecked() throws IOException {
        writeConf("billing.mode=sandbox\n%test.billing.mode=staging");
        Play.readConfiguration();
        ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("is \"staging\", which is not an allowed value.");
    }

    // --- reset on every configuration read ---

    @Test
    public void everyConfigurationReadEmptiesTheRegistry() throws IOException {
        writeConf("");
        ConfigRequirements.require("a plugin that is gone", "stale.key");
        Play.readConfiguration();
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void pluginsDeclareAfterTheResetAndOnlyOnce() throws IOException {
        BillingPlugin plugin = new BillingPlugin();
        Play.pluginCollection = new PluginCollection() {
            {
                addPlugin(plugin);
            }
        };
        writeConf("");
        // Play.init reads once and Play.start reads again; every DEV restart adds a read.
        Play.readConfiguration();
        Play.readConfiguration();
        Play.readConfiguration();
        assertThat(plugin.reads).isEqualTo(3);
        // The declaration made during the read is still there, so the reset ran before the
        // plugins were told, and three reads left one declaration behind.
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("(1 problem)")
            .hasMessageContaining("billing.apiKey (declared by " + BillingPlugin.class.getName() + ") is missing.");

        // A plugin that no longer declares takes its requirement with it.
        Play.pluginCollection.disablePlugin(plugin);
        Play.readConfiguration();
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    @Test
    public void aPluginDisabledInTheSameReadTakesItsDeclarationsWithIt() throws IOException {
        // plugins.disable is acted on by ConfigurablePluginDisablingPlugin, which comes last
        // in the broadcast: by then the plugin it switches off has already declared.
        BillingPlugin plugin = new BillingPlugin();
        ConfigurablePluginDisablingPlugin disabler = new ConfigurablePluginDisablingPlugin();
        disabler.index = 100000;
        Play.pluginCollection = new PluginCollection() {
            {
                addPlugin(plugin);
                addPlugin(disabler);
            }
        };
        try {
            writeConf("plugins.disable=" + BillingPlugin.class.getName());
            Play.readConfiguration();
            assertThat(plugin.reads).isEqualTo(1);
            assertThat(Play.pluginCollection.isEnabled(plugin)).isFalse();
            assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
        } finally {
            // The disabling plugin remembers what it disabled in a static; a read without
            // the key makes it forget.
            writeConf("");
            Play.readConfiguration();
        }
    }

    @Test
    public void aDisabledPluginDoesNotTakeAnEnabledSubclassesDeclarationsWithIt() throws IOException {
        // How a module's plugin is replaced: disable it and load a subclass. The subclass
        // inherits onConfigurationRead() and so declares under the parent's class, which
        // the disabled parent is an instance of too and comes first in the collection.
        BillingPlugin original = new BillingPlugin();
        original.index = 1;
        BillingPlugin replacement = new ReplacementBillingPlugin();
        replacement.index = 2;
        Play.pluginCollection = new PluginCollection() {
            {
                addPlugin(original);
                addPlugin(replacement);
            }
        };
        Play.pluginCollection.disablePlugin(original);
        writeConf("");
        Play.readConfiguration();
        assertThat(original.reads).isZero();
        assertThat(replacement.reads).isEqualTo(1);
        assertThatThrownBy(ConfigRequirements::check)
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("billing.apiKey (declared by " + BillingPlugin.class.getName() + ") is missing.");

        // With no plugin of the owning class left enabled, the declaration goes.
        Play.pluginCollection.disablePlugin(replacement);
        assertThatCode(ConfigRequirements::check).doesNotThrowAnyException();
    }

    // --- the framework's own declarations ---

    @Test
    public void logFormatAcceptsTextAndJsonInAnyLetterCaseOrNoValue() throws IOException {
        for (String format : new String[] {"text", "json", "TEXT", "Json", ""}) {
            writeConf("application.log.format=" + format);
            Play.readConfiguration();
            assertThatCode(ConfigRequirements::check).as("application.log.format=%s", format).doesNotThrowAnyException();
        }
        writeConf("");
        Play.readConfiguration();
        assertThatCode(ConfigRequirements::check).as("key left out").doesNotThrowAnyException();
    }

    @Test
    public void logFormatRejectsAnyOtherValueOnEveryRead() throws IOException {
        // Logger reads anything but json as text, so this typo used to select text silently.
        writeConf("application.log.format=jsno");
        for (int read = 1; read <= 2; read++) {
            Play.readConfiguration();
            assertThatThrownBy(ConfigRequirements::check)
                .as("read %d", read)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("(1 problem)")
                .hasMessageContaining("application.log.format (declared by Play framework) is \"jsno\", which is "
                    + "not an allowed value. Set it to one of: text, json.");
        }
    }

    // --- where the check runs ---

    @Test
    public void readingTheConfigurationNeverChecks() throws IOException {
        // play precompile, bundle and dist boot through Play.init, which reads the
        // configuration and returns without calling Play.start(). A declared key that is
        // unresolved or wrong must not fail there: CI packages without production variables.
        writeConf("application.log.format=${" + UNSET + "}");
        assertThatCode(Play::readConfiguration).doesNotThrowAnyException();
        writeConf("application.log.format=jsno");
        assertThatCode(Play::readConfiguration).doesNotThrowAnyException();
    }

    @Test
    public void startFailsWithEveryViolationInOneException() throws IOException {
        Play.pluginCollection = new PluginCollection() {
            {
                addPlugin(new BillingPlugin());
            }
        };
        writeConf("application.log.format=jsno");
        Play.Mode savedMode = Play.mode;
        boolean savedStandalone = Play.standalonePlayServer;
        String savedSecretKey = Play.secretKey;
        // PROD keeps start() from swapping the classloader and reloading plugins ahead of
        // the read, and a non-standalone server from registering a JVM shutdown hook.
        Play.mode = Play.Mode.PROD;
        Play.standalonePlayServer = false;
        try {
            assertThatThrownBy(Play::start)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("(2 problems)")
                .hasMessageContaining("application.log.format (declared by Play framework) is \"jsno\"")
                .hasMessageContaining("billing.apiKey (declared by " + BillingPlugin.class.getName() + ") is missing.");
            assertThat(Play.started).isFalse();
        } finally {
            Play.mode = savedMode;
            Play.standalonePlayServer = savedStandalone;
            Play.secretKey = savedSecretKey;
        }
    }

    /** Declares what it needs the way an application plugin would. */
    public static class BillingPlugin extends PlayPlugin {
        int reads;

        @Override
        public void onConfigurationRead() {
            reads++;
            ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
        }
    }

    /** Stands in for BillingPlugin and declares through the hook it inherits. */
    public static class ReplacementBillingPlugin extends BillingPlugin {
    }

    private void writeConf(String content) throws IOException {
        // application.secret has to be declared in this exact form or the read itself fails.
        Files.writeString(tempDir.resolve("conf/application.conf"),
                "application.name=test\napplication.secret=${PLAY_SECRET}\n" + content + "\n");
    }
}
