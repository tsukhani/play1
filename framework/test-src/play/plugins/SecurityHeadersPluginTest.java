package play.plugins;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;
import play.mvc.Http;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class SecurityHeadersPluginTest {

    private SecurityHeadersPlugin plugin;
    private Properties config;

    @BeforeEach
    public void setUp() {
        config = new Properties();
        new PlayBuilder().withConfiguration(config).build();
        plugin = new SecurityHeadersPlugin();

        Http.Response response = new Http.Response();
        response.out = new ByteArrayOutputStream();
        Http.Response.current.set(response);

        Http.Request request = Http.Request.createRequest(
                null, "GET", "/", "", null, null, null, null, false, 80, "localhost", false, null, null);
        Http.Request.current.set(request);
    }

    @Test
    public void allDefaultHeadersPresentWhenEnabled() {
        plugin.onApplicationStart();
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("X-XSS-Protection")).isEqualTo("0");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'self'");
    }

    @Test
    public void hstsNotPresentOnHttpRequests() {
        plugin.onApplicationStart();
        Http.Request.current().secure = false;
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    public void hstsPresentOnHttpsRequests() {
        plugin.onApplicationStart();
        Http.Request.current().secure = true;
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    public void masterSwitchDisablesAllHeaders() {
        config.setProperty("http.headers.enabled", "false");
        plugin.onApplicationStart();
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("X-Content-Type-Options")).isNull();
        assertThat(response.getHeader("X-Frame-Options")).isNull();
        assertThat(response.getHeader("Referrer-Policy")).isNull();
        assertThat(response.getHeader("X-XSS-Protection")).isNull();
        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    public void individualHeaderCanBeDisabledWithEmptyString() {
        config.setProperty("http.headers.xFrameOptions", "");
        plugin.onApplicationStart();
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("X-Frame-Options")).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    public void individualHeaderCanBeDisabledWithKeyword() {
        config.setProperty("http.headers.contentSecurityPolicy", "disabled");
        plugin.onApplicationStart();
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Content-Security-Policy")).isNull();
    }

    @Test
    public void applicationSetHeadersAreNotOverwritten() {
        plugin.onApplicationStart();

        Http.Response response = Http.Response.current();
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        plugin.onActionInvocationResult(null);

        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
    }

    @Test
    public void customHeaderValuesFromConfig() {
        config.setProperty("http.headers.xFrameOptions", "SAMEORIGIN");
        config.setProperty("http.headers.referrerPolicy", "no-referrer");
        config.setProperty("http.headers.contentSecurityPolicy", "default-src 'self'; script-src 'self'");
        plugin.onApplicationStart();
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'self'; script-src 'self'");
    }

    @Test
    public void hstsIncludeSubDomainsCanBeDisabled() {
        config.setProperty("http.headers.hsts.includeSubDomains", "false");
        plugin.onApplicationStart();
        Http.Request.current().secure = true;
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000");
    }

    @Test
    public void hstsPreloadFlag() {
        config.setProperty("http.headers.hsts.preload", "true");
        plugin.onApplicationStart();
        Http.Request.current().secure = true;
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains; preload");
    }

    @Test
    public void hstsCustomMaxAge() {
        config.setProperty("http.headers.hsts.maxAge", "86400");
        plugin.onApplicationStart();
        Http.Request.current().secure = true;
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Strict-Transport-Security"))
                .startsWith("max-age=86400");
    }

    @Test
    public void hstsCanBeDisabledIndependently() {
        config.setProperty("http.headers.hsts.enabled", "false");
        plugin.onApplicationStart();
        Http.Request.current().secure = true;
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Strict-Transport-Security")).isNull();
        assertThat(Http.Response.current().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    public void hstsNotOverwrittenIfAlreadySet() {
        plugin.onApplicationStart();
        Http.Request.current().secure = true;
        Http.Response.current().setHeader("Strict-Transport-Security", "max-age=0");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Strict-Transport-Security")).isEqualTo("max-age=0");
    }

    @Test
    public void pluginRegisteredInPlayPlugins() {
        PluginCollection pc = new PluginCollection();
        new PlayBuilder().build();
        pc.loadPlugins();

        SecurityHeadersPlugin pi = pc.getPluginInstance(SecurityHeadersPlugin.class);
        assertThat(pi).isNotNull();
        assertThat(pc.getEnabledPlugins()).contains(pi);
    }
}
