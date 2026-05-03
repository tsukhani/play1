package play.plugins;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;
import play.mvc.Http;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CorsPlugin} (PF-6). Exercises the plugin in isolation
 * without bootstrapping a real server — the integration coverage lives in
 * {@code integration.CorsFunctionalTest}.
 */
public class CorsPluginTest {

    private CorsPlugin plugin;
    private Properties config;

    @BeforeEach
    public void setUp() {
        config = new Properties();
        new PlayBuilder().withConfiguration(config).build();
        plugin = new CorsPlugin();

        Http.Response response = new Http.Response();
        response.out = new ByteArrayOutputStream();
        Http.Response.current.set(response);
    }

    @AfterEach
    public void tearDown() {
        Http.Request.current.remove();
        Http.Response.current.remove();
    }

    private Http.Request makeRequest(String method, String origin) {
        Map<String, Http.Header> headers = new HashMap<>();
        if (origin != null) {
            headers.put("origin", new Http.Header("origin", origin));
        }
        Http.Request request = Http.Request.createRequest(new Http.Request.RequestData(
                null, method, "/api/data", "", null, null, null, "localhost", false, 80, "localhost", false,
                headers, null));
        Http.Request.current.set(request);
        return request;
    }

    // --- AC: disabled by default ---

    @Test
    public void disabledByDefault() {
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // --- AC: addsAllowOriginOnEnabledRequest ---

    @Test
    public void addsAllowOriginOnEnabledRequest() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
    }

    // --- AC: preflightShortCircuits204 ---

    @Test
    public void preflightShortCircuits204() throws Exception {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", "http://example.com");
        request.headers.put("access-control-request-headers",
                new Http.Header("access-control-request-headers", "Content-Type"));

        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).as("rawInvocation must short-circuit preflight").isTrue();
        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(204);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,POST,PUT,DELETE,OPTIONS");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualTo("Content-Type,Authorization");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("3600");
    }

    // --- AC: wildcardOriginIncompatibleWithCredentials ---

    @Test
    public void wildcardOriginIncompatibleWithCredentials() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "*");
        config.setProperty("cors.allowCredentials", "true");
        plugin.onApplicationStart();

        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        // Per spec: cannot combine * with credentials. Plugin must reflect Origin.
        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("http://example.com");
        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    // --- AC: customExposedHeaders ---

    @Test
    public void customExposedHeaders() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.exposedHeaders", "X-Foo,X-Bar");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Expose-Headers"))
                .isEqualTo("X-Foo,X-Bar");
    }

    // --- AC: originReflectionForSpecificList ---

    @Test
    public void originReflectionForSpecificList() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "https://a.com,https://b.com");
        plugin.onApplicationStart();
        makeRequest("GET", "https://a.com");
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://a.com");
        assertThat(response.getHeader("Vary")).contains("Origin");
    }

    // --- Negative cases ---

    @Test
    public void preflightIgnoredWhenDisabled() throws Exception {
        plugin.onApplicationStart();
        Http.Request request = makeRequest("OPTIONS", "http://example.com");

        assertThat(plugin.rawInvocation(request, Http.Response.current())).isFalse();
    }

    @Test
    public void preflightIgnoredForNonOptionsMethod() throws Exception {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        Http.Request request = makeRequest("GET", "http://example.com");

        assertThat(plugin.rawInvocation(request, Http.Response.current())).isFalse();
    }

    @Test
    public void preflightIgnoredWithoutOriginHeader() throws Exception {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        Http.Request request = makeRequest("OPTIONS", null);

        assertThat(plugin.rawInvocation(request, Http.Response.current())).isFalse();
    }

    @Test
    public void specificOriginRejected() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "https://a.com");
        plugin.onApplicationStart();
        makeRequest("GET", "https://evil.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    public void preflightWithSpecificOriginRejected() throws Exception {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "https://a.com");
        plugin.onApplicationStart();
        Http.Request request = makeRequest("OPTIONS", "https://evil.com");

        assertThat(plugin.rawInvocation(request, Http.Response.current())).isFalse();
    }

    @Test
    public void noCorsHeadersWithoutOrigin() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        makeRequest("GET", null);
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // --- Coexistence with SecurityHeadersPlugin: don't blow away pre-set values ---

    @Test
    public void doesNotOverwriteAlreadySetHeaders() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();

        Http.Response.current().setHeader("Access-Control-Allow-Origin", "http://app-set.com");
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://app-set.com");
    }

    @Test
    public void varyHeaderAppendedToExisting() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "https://a.com");
        plugin.onApplicationStart();

        Http.Response.current().setHeader("Vary", "Accept-Encoding");
        makeRequest("GET", "https://a.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Vary")).isEqualTo("Accept-Encoding, Origin");
    }

    // --- Plugin registration ---

    @Test
    public void pluginRegisteredInPlayPlugins() {
        PluginCollection pc = new PluginCollection();
        pc.loadPlugins();

        CorsPlugin pi = pc.getPluginInstance(CorsPlugin.class);
        assertThat(pi).isNotNull();
        assertThat(pc.getEnabledPlugins()).contains(pi);
    }
}
