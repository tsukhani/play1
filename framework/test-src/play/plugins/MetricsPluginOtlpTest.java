package play.plugins;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;
import play.libs.Metrics;
import play.mvc.Http;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetricsPlugin}'s OTLP exporter wiring (PF-82).
 * Exercises the plugin's lifecycle without going through Netty.
 */
public class MetricsPluginOtlpTest {

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

    @Test
    public void otlpDisabledWhenEndpointEmpty() {
        plugin.onApplicationStart();

        assertThat(Metrics.registry()).isInstanceOf(PrometheusMeterRegistry.class);
    }

    @Test
    public void otlpInstalledWhenEndpointSet() {
        config.setProperty("metrics.otlp.endpoint", "http://localhost:4318/v1/metrics");
        plugin.onApplicationStart();

        MeterRegistry installed = Metrics.registry();
        assertThat(installed).isInstanceOf(CompositeMeterRegistry.class);

        Set<MeterRegistry> components = ((CompositeMeterRegistry) installed).getRegistries();
        assertThat(components).hasSize(2);
        assertThat(components).hasAtLeastOneElementOfType(PrometheusMeterRegistry.class);
        assertThat(components).hasAtLeastOneElementOfType(OtlpMeterRegistry.class);
    }

    @Test
    public void customMetricPublishesToBothBackends() {
        config.setProperty("metrics.otlp.endpoint", "http://localhost:4318/v1/metrics");
        plugin.onApplicationStart();

        Counter counter = Metrics.counter("foo");
        counter.increment(3);

        // Both component registries should see the counter.
        CompositeMeterRegistry composite = (CompositeMeterRegistry) Metrics.registry();
        PrometheusMeterRegistry prom = null;
        OtlpMeterRegistry otlp = null;
        for (MeterRegistry r : composite.getRegistries()) {
            if (r instanceof PrometheusMeterRegistry) {
                prom = (PrometheusMeterRegistry) r;
            } else if (r instanceof OtlpMeterRegistry) {
                otlp = (OtlpMeterRegistry) r;
            }
        }
        assertThat(prom).isNotNull();
        assertThat(otlp).isNotNull();

        // Prometheus scrape body contains the counter total.
        assertThat(prom.scrape()).contains("foo_total 3");

        // OTLP registry has the counter registered. Its observed value is
        // governed by step-based aggregation (cumulative-since-last-rollover)
        // so we don't assert a numeric count here — the wiring assertion is
        // that the meter exists on both component registries.
        Counter otlpCounter = otlp.find("foo").counter();
        assertThat(otlpCounter).isNotNull();
    }

    @Test
    public void headersParsedCorrectly() {
        config.setProperty("metrics.otlp.headers", "A=1,B=2");
        OtlpConfig built = MetricsPlugin.buildOtlpConfig("http://example.invalid/v1/metrics");

        Map<String, String> expected = new HashMap<>();
        expected.put("A", "1");
        expected.put("B", "2");
        assertThat(built.headers()).isEqualTo(expected);
    }

    @Test
    public void resourceAttributesParsedCorrectly() {
        config.setProperty("metrics.otlp.resourceAttributes", "service.name=my-app,service.version=1.0");
        OtlpConfig built = MetricsPlugin.buildOtlpConfig("http://example.invalid/v1/metrics");

        Map<String, String> expected = new HashMap<>();
        expected.put("service.name", "my-app");
        expected.put("service.version", "1.0");
        assertThat(built.resourceAttributes()).isEqualTo(expected);
    }

    @Test
    public void shortFormStepParsedCorrectly() {
        assertThat(MetricsPlugin.parseStep("60s")).isEqualTo(Duration.ofSeconds(60));
        assertThat(MetricsPlugin.parseStep("1m")).isEqualTo(Duration.ofMinutes(1));
        assertThat(MetricsPlugin.parseStep("PT30S")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    public void malformedStepFallsBackToDefault() {
        assertThat(MetricsPlugin.parseStep("garbage")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    public void onApplicationStopClosesBothRegistries() {
        config.setProperty("metrics.otlp.endpoint", "http://localhost:4318/v1/metrics");
        plugin.onApplicationStart();

        CompositeMeterRegistry composite = (CompositeMeterRegistry) Metrics.registry();
        PrometheusMeterRegistry prom = null;
        OtlpMeterRegistry otlp = null;
        for (MeterRegistry r : composite.getRegistries()) {
            if (r instanceof PrometheusMeterRegistry) {
                prom = (PrometheusMeterRegistry) r;
            } else if (r instanceof OtlpMeterRegistry) {
                otlp = (OtlpMeterRegistry) r;
            }
        }
        assertThat(prom).isNotNull();
        assertThat(otlp).isNotNull();

        plugin.onApplicationStop();

        assertThat(prom.isClosed()).isTrue();
        assertThat(otlp.isClosed()).isTrue();
        assertThat(Metrics.registry().getClass().getSimpleName()).isEqualTo("SimpleMeterRegistry");
    }
}
