package play.plugins;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.libs.Metrics;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in Micrometer-based metrics with a Prometheus exposition endpoint
 * (PF-13).
 *
 * <p>Configuration in application.conf:</p>
 * <pre>
 * metrics.enabled=true             # default true
 * metrics.basePath=/@metrics       # default /@metrics
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
 * <p>OTLP push exporter is intentionally out of scope for this plugin —
 * Prometheus scrape is the baseline; OTLP can be layered as a follow-up.</p>
 */
public class MetricsPlugin extends PlayPlugin {

    private boolean enabled;
    private String basePath;
    private PrometheusMeterRegistry prometheusRegistry;
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
        Metrics.install(prometheusRegistry);
        bindJvmMetrics(prometheusRegistry);

        Logger.info("MetricsPlugin enabled at %s", basePath);
    }

    @Override
    public void onApplicationStop() {
        if (prometheusRegistry != null) {
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
            prometheusRegistry.close();
            prometheusRegistry = null;
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
}
