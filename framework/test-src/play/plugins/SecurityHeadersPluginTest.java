package play.plugins;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;
import play.server.SecurityHeadersPolicy;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle tests for {@link SecurityHeadersPlugin}. The plugin's job is loading config and
 * installing a {@link SecurityHeadersPolicy}; the policy's behavior is exercised in
 * {@link play.server.SecurityHeadersPolicyTest}.
 */
public class SecurityHeadersPluginTest {

    private SecurityHeadersPlugin plugin;
    private Properties config;

    @BeforeEach
    public void setUp() {
        SecurityHeadersPolicy.install(SecurityHeadersPolicy.DISABLED);
        config = new Properties();
        new PlayBuilder().withConfiguration(config).build();
        plugin = new SecurityHeadersPlugin();
    }

    @AfterEach
    public void tearDown() {
        SecurityHeadersPolicy.install(SecurityHeadersPolicy.DISABLED);
    }

    @Test
    public void onConfigurationReadInstallsPolicy() {
        plugin.onConfigurationRead();

        SecurityHeadersPolicy installed = SecurityHeadersPolicy.current();
        assertThat(installed.isEnabled()).isTrue();
        assertThat(installed).isNotSameAs(SecurityHeadersPolicy.DISABLED);

        HttpHeaders headers = new DefaultHttpHeaders();
        installed.applyTo(headers, false);
        assertThat(headers.get("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    public void masterSwitchOffInstallsDisabled() {
        config.setProperty("http.headers.enabled", "false");
        plugin.onConfigurationRead();

        assertThat(SecurityHeadersPolicy.current()).isSameAs(SecurityHeadersPolicy.DISABLED);
    }

    @Test
    public void onApplicationStopRevertsToDisabled() {
        plugin.onConfigurationRead();
        assertThat(SecurityHeadersPolicy.current().isEnabled()).isTrue();

        plugin.onApplicationStop();

        assertThat(SecurityHeadersPolicy.current()).isSameAs(SecurityHeadersPolicy.DISABLED);
    }

    @Test
    public void configReloadReinstallsPolicy() {
        // First read: defaults.
        plugin.onConfigurationRead();
        HttpHeaders before = new DefaultHttpHeaders();
        SecurityHeadersPolicy.current().applyTo(before, false);
        assertThat(before.get("X-Frame-Options")).isEqualTo("DENY");

        // Edit config, reload: hot-reload path.
        config.setProperty("http.headers.xFrameOptions", "SAMEORIGIN");
        plugin.onConfigurationRead();
        HttpHeaders after = new DefaultHttpHeaders();
        SecurityHeadersPolicy.current().applyTo(after, false);
        assertThat(after.get("X-Frame-Options")).isEqualTo("SAMEORIGIN");
    }

    @Test
    public void pluginRegisteredInPlayPlugins() {
        PluginCollection pc = new PluginCollection();
        pc.loadPlugins();

        SecurityHeadersPlugin pi = pc.getPluginInstance(SecurityHeadersPlugin.class);
        assertThat(pi).isNotNull();
        assertThat(pc.getEnabledPlugins()).contains(pi);
    }
}
