package play.plugins;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;
import play.mvc.Http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HealthCheckPlugin} (PF-11). Exercises the plugin's
 * {@code rawInvocation} hook directly without bootstrapping a Netty server.
 */
public class HealthCheckPluginTest {

    private HealthCheckPlugin plugin;
    private Properties config;

    @BeforeEach
    public void setUp() {
        config = new Properties();
        new PlayBuilder().withConfiguration(config).build();
        plugin = new HealthCheckPlugin();

        Http.Response response = new Http.Response();
        response.out = new ByteArrayOutputStream();
        Http.Response.current.set(response);
    }

    @AfterEach
    public void tearDown() {
        Http.Request.current.remove();
        Http.Response.current.remove();
        HealthCheckPlugin.clearRegistryForTesting();
    }

    private Http.Request makeRequest(String path, String authHeader) {
        Map<String, Http.Header> headers = new HashMap<>();
        if (authHeader != null) {
            headers.put("authorization", new Http.Header("authorization", authHeader));
        }
        Http.Request request = Http.Request.createRequest(new Http.Request.RequestData(
                null, "GET", path, "", null, null, null, "localhost", false, 80, "localhost", false,
                headers, null));
        Http.Request.current.set(request);
        return request;
    }

    private String responseBody(Http.Response response) {
        return ((ByteArrayOutputStream) response.out).toString(StandardCharsets.UTF_8);
    }

    @Test
    public void liveAlwaysReturns200() throws Exception {
        plugin.onConfigurationRead();
        Http.Request request = makeRequest("/@health/live", null);

        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isTrue();
        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(200);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(responseBody(response)).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    public void readyReturns200WhenAllChecksUp() throws Exception {
        plugin.onConfigurationRead();
        HealthCheckPlugin.register(new FakeCheck("db", HealthCheck.Status.UP));
        HealthCheckPlugin.register(new FakeCheck("cache", HealthCheck.Status.UP));

        Http.Request request = makeRequest("/@health/ready", null);
        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isTrue();
        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(200);
        String body = responseBody(response);
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).contains("\"name\":\"db\"");
        assertThat(body).contains("\"name\":\"cache\"");
    }

    @Test
    public void readyReturns503WhenAnyCheckDown() throws Exception {
        plugin.onConfigurationRead();
        HealthCheckPlugin.register(new FakeCheck("db", HealthCheck.Status.UP));
        HealthCheckPlugin.register(new FakeCheck("cache", HealthCheck.Status.DOWN));

        Http.Request request = makeRequest("/@health/ready", null);
        plugin.rawInvocation(request, Http.Response.current());

        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(503);
        String body = responseBody(response);
        assertThat(body).contains("\"status\":\"DOWN\"");
        assertThat(body).contains("\"name\":\"cache\"");
        assertThat(body).contains("\"status\":\"DOWN\"");
    }

    @Test
    public void readyReturns200WhenNoChecksRegistered() throws Exception {
        plugin.onConfigurationRead();

        Http.Request request = makeRequest("/@health/ready", null);
        plugin.rawInvocation(request, Http.Response.current());

        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(200);
        String body = responseBody(response);
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).contains("\"checks\":[]");
    }

    @Test
    public void customBasePathHonored() throws Exception {
        config.setProperty("health.basePath", "/healthz");
        plugin.onConfigurationRead();

        // /healthz/live handled
        Http.Request liveReq = makeRequest("/healthz/live", null);
        assertThat(plugin.rawInvocation(liveReq, Http.Response.current())).isTrue();
        assertThat(Http.Response.current().status).isEqualTo(200);

        // Default path no longer handled
        Http.Response.current.set(freshResponse());
        Http.Request defaultReq = makeRequest("/@health/live", null);
        assertThat(plugin.rawInvocation(defaultReq, Http.Response.current())).isFalse();
    }

    @Test
    public void authTokenRequiredWhenConfigured() throws Exception {
        config.setProperty("health.authToken", "secret");
        plugin.onConfigurationRead();

        // No header -> 401
        plugin.rawInvocation(makeRequest("/@health/live", null), Http.Response.current());
        assertThat(Http.Response.current().status).isEqualTo(401);

        // Wrong token -> 401
        Http.Response.current.set(freshResponse());
        plugin.rawInvocation(makeRequest("/@health/live", "Bearer wrong"), Http.Response.current());
        assertThat(Http.Response.current().status).isEqualTo(401);

        // Correct token -> 200
        Http.Response.current.set(freshResponse());
        plugin.rawInvocation(makeRequest("/@health/live", "Bearer secret"), Http.Response.current());
        assertThat(Http.Response.current().status).isEqualTo(200);
    }

    @Test
    public void customHealthCheckRegistration() throws Exception {
        plugin.onConfigurationRead();
        HealthCheckPlugin.register(new FakeCheck("custom", HealthCheck.Status.DOWN));

        Http.Request request = makeRequest("/@health/ready", null);
        plugin.rawInvocation(request, Http.Response.current());

        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(503);
        String body = responseBody(response);
        assertThat(body).contains("\"name\":\"custom\"");
        assertThat(body).contains("\"status\":\"DOWN\"");
    }

    private Http.Response freshResponse() {
        Http.Response r = new Http.Response();
        r.out = new ByteArrayOutputStream();
        return r;
    }

    private static final class FakeCheck implements HealthCheck {
        private final String name;
        private final Status status;

        FakeCheck(String name, Status status) {
            this.name = name;
            this.status = status;
        }

        @Override public String name() { return name; }
        @Override public Status check() { return status; }
    }
}
