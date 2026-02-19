package play.plugins;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;
import play.mvc.Http;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

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

    private Http.Request makeRequest(String method, String origin) {
        Map<String, Http.Header> headers = new HashMap<>();
        if (origin != null) {
            headers.put("origin", new Http.Header("origin", origin));
        }
        Http.Request request = Http.Request.createRequest(
                null, method, "/api/data", "", null, null, null, null, false, 80, "localhost", false, headers, null);
        Http.Request.current.set(request);
        return request;
    }

    // --- Disabled by default ---

    @Test
    public void disabledByDefault() {
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // --- Preflight (OPTIONS) tests ---

    @Test
    public void preflightReturns204WithCorsHeaders() throws Exception {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", "http://example.com");
        request.headers.put("access-control-request-headers", new Http.Header("access-control-request-headers", "Content-Type"));

        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isTrue();
        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(204);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("GET");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isNotNull();
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("3600");
    }

    @Test
    public void preflightIgnoredWhenDisabled() throws Exception {
        // cors.enabled defaults to false
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", "http://example.com");
        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isFalse();
    }

    @Test
    public void preflightIgnoredForNonOptionsMethod() throws Exception {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("GET", "http://example.com");
        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isFalse();
    }

    @Test
    public void preflightIgnoredWithoutOriginHeader() throws Exception {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", null);
        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isFalse();
    }

    // --- Regular response tests ---

    @Test
    public void corsHeadersOnRegularResponseWithWildcard() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
    }

    @Test
    public void noCorsHeadersWithoutOrigin() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        makeRequest("GET", null);
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // --- Specific origins ---

    @Test
    public void specificOriginAllowed() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "http://example.com,http://other.com");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://example.com");
        assertThat(response.getHeader("Vary")).contains("Origin");
    }

    @Test
    public void specificOriginRejected() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "http://example.com");
        plugin.onApplicationStart();
        makeRequest("GET", "http://evil.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    public void preflightWithSpecificOriginAllowed() throws Exception {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "http://example.com");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", "http://example.com");
        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isTrue();
        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://example.com");
        assertThat(Http.Response.current().getHeader("Vary")).contains("Origin");
    }

    @Test
    public void preflightWithSpecificOriginRejected() throws Exception {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "http://example.com");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", "http://evil.com");
        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isFalse();
    }

    // --- Credentials ---

    @Test
    public void credentialsHeaderWhenEnabled() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowCredentials", "true");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        Http.Response response = Http.Response.current();
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    public void noCredentialsHeaderByDefault() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    public void credentialsWithWildcardEchosOrigin() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowCredentials", "true");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        // Per spec, when credentials are used, wildcard * is not allowed;
        // the actual origin must be echoed back
        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://example.com");
    }

    // --- Exposed headers ---

    @Test
    public void exposedHeadersWhenConfigured() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.exposedHeaders", "X-Custom-Header,X-Request-Id");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Expose-Headers"))
                .isEqualTo("X-Custom-Header,X-Request-Id");
    }

    @Test
    public void noExposedHeadersByDefault() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Expose-Headers")).isNull();
    }

    // --- Custom methods and headers ---

    @Test
    public void customAllowedMethods() throws Exception {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedMethods", "GET,POST");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", "http://example.com");
        request.headers.put("access-control-request-headers", new Http.Header("access-control-request-headers", "Content-Type"));
        plugin.rawInvocation(request, Http.Response.current());

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,POST");
    }

    @Test
    public void customMaxAge() throws Exception {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.maxAge", "7200");
        plugin.onApplicationStart();

        Http.Request request = makeRequest("OPTIONS", "http://example.com");
        plugin.rawInvocation(request, Http.Response.current());

        assertThat(Http.Response.current().getHeader("Access-Control-Max-Age")).isEqualTo("7200");
    }

    // --- Application-set headers are not overwritten ---

    @Test
    public void applicationSetCorsHeadersNotOverwritten() {
        config.setProperty("cors.enabled", "true");
        plugin.onApplicationStart();

        Http.Response.current().setHeader("Access-Control-Allow-Origin", "http://custom.com");
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://custom.com");
    }

    // --- Vary header ---

    @Test
    public void varyHeaderNotSetForWildcardOrigin() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "*");
        config.setProperty("cors.allowCredentials", "false");
        plugin.onApplicationStart();
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        // With wildcard and no credentials, Vary: Origin is not needed
        assertThat(Http.Response.current().getHeader("Vary")).isNull();
    }

    @Test
    public void varyHeaderAppendedToExisting() {
        config.setProperty("cors.enabled", "true");
        config.setProperty("cors.allowedOrigins", "http://example.com");
        plugin.onApplicationStart();

        Http.Response.current().setHeader("Vary", "Accept-Encoding");
        makeRequest("GET", "http://example.com");
        plugin.onActionInvocationResult(null);

        assertThat(Http.Response.current().getHeader("Vary")).isEqualTo("Accept-Encoding, Origin");
    }

    // --- Plugin registration ---

    @Test
    public void pluginRegisteredInPlayPlugins() {
        PluginCollection pc = new PluginCollection();
        new PlayBuilder().build();
        pc.loadPlugins();

        CorsPlugin pi = pc.getPluginInstance(CorsPlugin.class);
        assertThat(pi).isNotNull();
        assertThat(pc.getEnabledPlugins()).contains(pi);
    }
}
