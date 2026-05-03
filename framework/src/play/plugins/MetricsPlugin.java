package play.plugins;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.libs.Metrics;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in Micrometer-based metrics with a Prometheus exposition endpoint
 * (PF-13) and an optional OTLP push exporter (PF-82).
 *
 * <p>Configuration in application.conf:</p>
 * <pre>
 * metrics.enabled=true                       # default true
 * metrics.basePath=/@metrics                 # default /@metrics
 * metrics.otlp.endpoint=                     # empty = OTLP disabled
 * metrics.otlp.step=60s                      # push interval
 * metrics.otlp.headers=K1=V1,K2=V2           # OTLP HTTP headers
 * metrics.otlp.resourceAttributes=k=v,k2=v2  # OTLP resource attributes
 * </pre>
 *
 * <p>Auto-registers Micrometer's bundled JVM binders (memory, GC, threads,
 * classloader). HTTP request count + duration are recorded by
 * {@code ActionInvoker.invoke()} into the timer
 * {@code http_server_requests} (tags: {@code method}, {@code status},
 * {@code route}).</p>
 *
 * <p>Custom application metrics use {@link play.libs.Metrics}, which forwards
 * to the registry installed here. When the plugin is disabled the static
 * facade still works (in-memory {@link SimpleMeterRegistry}); only the
 * exposition endpoint and JVM binders are skipped.</p>
 *
 * <p>When {@code metrics.otlp.endpoint} is set the installed registry is a
 * {@link CompositeMeterRegistry} containing both the Prometheus registry
 * (which still backs the {@code /@metrics} scrape endpoint) and an
 * {@link OtlpMeterRegistry} that pushes to the configured OTLP collector on
 * the step interval. Custom meters and JVM binders register on the composite
 * once and propagate to both backends.</p>
 */
public class MetricsPlugin extends PlayPlugin {

    private static final Duration DEFAULT_OTLP_STEP = Duration.ofSeconds(60);

    private boolean enabled;
    private String basePath;
    private PrometheusMeterRegistry prometheusRegistry;
    private OtlpMeterRegistry otlpRegistry;
    private final List<MeterBinder> jvmBinders = new ArrayList<>();

    @Override
    public void onApplicationStart() {
        enabled = Boolean.parseBoolean(Play.configuration.getProperty("metrics.enabled", "true"));
        basePath = Play.configuration.getProperty("metrics.basePath", "/@metrics").trim();

        if (!enabled) {
            Logger.info("MetricsPlugin disabled via metrics.enabled=false");
            return;
        }

        prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        String otlpEndpoint = Play.configuration.getProperty("metrics.otlp.endpoint", "").trim();
        MeterRegistry installed;
        if (otlpEndpoint.isEmpty()) {
            installed = prometheusRegistry;
        } else {
            otlpRegistry = new OtlpMeterRegistry(buildOtlpConfig(otlpEndpoint), Clock.SYSTEM);
            CompositeMeterRegistry composite = new CompositeMeterRegistry(Clock.SYSTEM);
            composite.add(prometheusRegistry);
            composite.add(otlpRegistry);
            installed = composite;
            Logger.info("MetricsPlugin OTLP exporter pushing to %s", otlpEndpoint);
        }

        Metrics.install(installed);
        bindJvmMetrics(installed);

        Logger.info("MetricsPlugin enabled at %s", basePath);
    }

    @Override
    public void onApplicationStop() {
        if (prometheusRegistry != null || otlpRegistry != null) {
            for (MeterBinder binder : jvmBinders) {
                if (binder instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) binder).close();
                    } catch (Exception e) {
                        Logger.warn(e, "Failed to close JVM metric binder %s", binder.getClass().getSimpleName());
                    }
                }
            }
            jvmBinders.clear();
            if (otlpRegistry != null) {
                otlpRegistry.close();
                otlpRegistry = null;
            }
            if (prometheusRegistry != null) {
                prometheusRegistry.close();
                prometheusRegistry = null;
            }
        }
        Metrics.install(new SimpleMeterRegistry());
    }

    /**
     * Short-circuit the configured base path with the Prometheus text-format
     * scrape body. When the plugin is disabled the request falls through to
     * the normal router (which will 404 if no app route matches).
     */
    @Override
    public boolean rawInvocation(Request request, Response response) throws Exception {
        if (!enabled || prometheusRegistry == null) {
            return false;
        }
        if (!request.path.equals(basePath)) {
            return false;
        }
        return handleScrape(response);
    }

    /**
     * Render the Prometheus exposition body to the supplied response. Exposed
     * package-private so unit tests can drive it without bootstrapping the
     * raw-invocation pipeline.
     */
    boolean handleScrape(Response response) {
        response.status = 200;
        response.contentType = "text/plain; version=0.0.4; charset=utf-8";
        response.print(prometheusRegistry.scrape());
        return true;
    }

    private void bindJvmMetrics(MeterRegistry registry) {
        jvmBinders.add(new JvmMemoryMetrics());
        jvmBinders.add(new JvmGcMetrics());
        jvmBinders.add(new JvmThreadMetrics());
        jvmBinders.add(new ClassLoaderMetrics());
        for (MeterBinder binder : jvmBinders) {
            binder.bindTo(registry);
        }
    }

    /**
     * Build an {@link OtlpConfig} from {@code metrics.otlp.*} configuration
     * keys. Package-private for unit tests.
     */
    static OtlpConfig buildOtlpConfig(String endpoint) {
        String stepRaw = Play.configuration.getProperty("metrics.otlp.step", "60s").trim();
        Duration step = parseStep(stepRaw);
        Map<String, String> headers = parseKvList(Play.configuration.getProperty("metrics.otlp.headers", ""));
        Map<String, String> resourceAttrs = parseKvList(
                Play.configuration.getProperty("metrics.otlp.resourceAttributes", ""));
        return new OtlpConfig() {
            @Override
            public String url() {
                return endpoint;
            }

            @Override
            public Duration step() {
                return step;
            }

            @Override
            public Map<String, String> headers() {
                return headers;
            }

            @Override
            public Map<String, String> resourceAttributes() {
                return resourceAttrs;
            }

            @Override
            public String prefix() {
                return "otlp";
            }

            @Override
            public String get(String key) {
                return null;
            }
        };
    }

    /**
     * Parse a duration string. Accepts ISO-8601 (PT60S) and the common short
     * forms ({@code 60s}, {@code 1m}, {@code 500ms}). Logs a warning and
     * returns the 60s default on any malformed input.
     */
    static Duration parseStep(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_OTLP_STEP;
        }
        String iso = raw.startsWith("PT") || raw.startsWith("pt")
                ? raw.toUpperCase()
                : "PT" + raw.toUpperCase();
        try {
            return Duration.parse(iso);
        } catch (DateTimeParseException e) {
            Logger.warn("Malformed metrics.otlp.step '%s' — falling back to %s", raw, DEFAULT_OTLP_STEP);
            return DEFAULT_OTLP_STEP;
        }
    }

    /**
     * Parse a comma-separated K=V list into a map. Empty input returns an
     * empty map. Entries without {@code =} or with empty keys are skipped.
     */
    static Map<String, String> parseKvList(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        for (String entry : trimmed.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = entry.substring(0, eq).trim();
            String v = entry.substring(eq + 1).trim();
            if (!k.isEmpty()) {
                result.put(k, v);
            }
        }
        return result;
    }
}
