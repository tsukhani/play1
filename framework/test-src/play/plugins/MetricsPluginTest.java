package play.plugins;

import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;
import play.libs.Metrics;
import play.mvc.Http;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetricsPlugin} (PF-13). Drives the plugin
 * directly — no real Netty pipeline needed.
 */
public class MetricsPluginTest {

    private MetricsPlugin plugin;
    private Properties config;

    @BeforeEach
    public void setUp() {
        config = new Properties();
        new PlayBuilder().withConfiguration(config).build();
        plugin = new MetricsPlugin();

        Http.Response response = new Http.Response();
        response.out = new ByteArrayOutputStream();
        Http.Response.current.set(response);
    }

    @AfterEach
    public void tearDown() {
        plugin.onApplicationStop();
        Http.Request.current.remove();
        Http.Response.current.remove();
    }

    private Http.Request makeRequest(String path) {
        Http.Request request = Http.Request.createRequest(new Http.Request.RequestData(
                null, "GET", path, "", null, null, null, "localhost", false, 80, "localhost", false,
                new HashMap<>(), null));
        Http.Request.current.set(request);
        return request;
    }

    @Test
    public void prometheusOutputContainsRegisteredCounter() throws Exception {
        plugin.onApplicationStart();

        Counter foo = Metrics.counter("foo");
        foo.increment(7);

        Http.Request request = makeRequest("/@metrics");
        boolean handled = plugin.rawInvocation(request, Http.Response.current());

        assertThat(handled).isTrue();
        Http.Response response = Http.Response.current();
        assertThat(response.status).isEqualTo(200);
        assertThat(response.contentType).isEqualTo("text/plain; version=0.0.4; charset=utf-8");
        String body = ((ByteArrayOutputStream) response.out).toString();
        // Prometheus exposition lines: HELP/TYPE comments + the named meter.
        assertThat(body).contains("# HELP foo");
        assertThat(body).contains("# TYPE foo");
        assertThat(body).contains("foo_total 7");
    }

    @Test
    public void jvmMetricsRegistered() {
        plugin.onApplicationStart();

        // Micrometer's JvmMemoryMetrics binder publishes "jvm.memory.used"
        // gauges — at least one should exist after onApplicationStart wires
        // up the binders.
        assertThat(Metrics.registry().find("jvm.memory.used").gauge()).isNotNull();
        assertThat(Metrics.registry().find("jvm.threads.live").gauge()).isNotNull();
        assertThat(Metrics.registry().find("jvm.classes.loaded").gauge()).isNotNull();
    }

    @Test
    public void customBasePathHonored() throws Exception {
        config.setProperty("metrics.basePath", "/custom-metrics");
        plugin.onApplicationStart();

        // Default path no longer matches.
        Http.Request defaultPath = makeRequest("/@metrics");
        assertThat(plugin.rawInvocation(defaultPath, Http.Response.current())).isFalse();

        // Custom path matches.
        Http.Response response2 = new Http.Response();
        response2.out = new ByteArrayOutputStream();
        Http.Response.current.set(response2);

        Http.Request customPath = makeRequest("/custom-metrics");
        assertThat(plugin.rawInvocation(customPath, Http.Response.current())).isTrue();
        assertThat(Http.Response.current().status).isEqualTo(200);
    }

    @Test
    public void disabledFlagShortCircuits() throws Exception {
        config.setProperty("metrics.enabled", "false");
        plugin.onApplicationStart();

        // No JVM binders registered when disabled — registry stays the
        // default SimpleMeterRegistry from Metrics' static init.
        assertThat(Metrics.registry().find("jvm.memory.used").gauge()).isNull();

        // /@metrics is not handled by the plugin: rawInvocation must
        // return false so the request falls through to the normal router.
        Http.Request request = makeRequest("/@metrics");
        assertThat(plugin.rawInvocation(request, Http.Response.current())).isFalse();
    }

    @Test
    public void onApplicationStopRestoresSimpleRegistry() {
        plugin.onApplicationStart();
        assertThat(Metrics.registry().getClass().getSimpleName()).isEqualTo("PrometheusMeterRegistry");

        plugin.onApplicationStop();
        assertThat(Metrics.registry().getClass().getSimpleName()).isEqualTo("SimpleMeterRegistry");
    }

    @Test
    public void pluginRegisteredInPlayPlugins() {
        PluginCollection pc = new PluginCollection();
        pc.loadPlugins();

        MetricsPlugin pi = pc.getPluginInstance(MetricsPlugin.class);
        assertThat(pi).isNotNull();
        assertThat(pc.getEnabledPlugins()).contains(pi);
    }
}
