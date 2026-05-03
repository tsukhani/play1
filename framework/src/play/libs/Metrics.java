package play.libs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.function.ToDoubleFunction;

/**
 * Static facade for application metrics (PF-13). Thin wrappers around a
 * Micrometer {@link MeterRegistry} that {@code play.plugins.MetricsPlugin}
 * swaps in at boot.
 *
 * <p>The default registry is an in-memory {@link SimpleMeterRegistry} so calls
 * to {@code Metrics.counter()} and friends work even when the plugin is
 * disabled — the meters simply have no exposition path. Once the plugin runs
 * its {@code onApplicationStart()}, a {@code PrometheusMeterRegistry} is
 * installed and the same call sites scrape via {@code /@metrics}.</p>
 */
public final class Metrics {

    private static volatile MeterRegistry registry = new SimpleMeterRegistry();

    private Metrics() {
    }

    /**
     * The currently installed meter registry. Plugins, JVM binders, and
     * advanced callers that need direct Micrometer API access use this.
     */
    public static MeterRegistry registry() {
        return registry;
    }

    /**
     * Swap the registry. Called by {@code MetricsPlugin} on application
     * start/stop. Public so plugins outside this package can install a
     * registry, but application code should generally not need to.
     */
    public static void install(MeterRegistry newRegistry) {
        registry = newRegistry;
    }

    /**
     * Look up or create a counter on the active registry.
     *
     * @param name meter name (Micrometer's dotted naming convention)
     * @param tags optional key/value pairs (must be even number of strings)
     */
    public static Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    /**
     * Look up or create a timer on the active registry.
     *
     * @param name meter name
     * @param tags optional key/value pairs
     */
    public static Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(registry);
    }

    /**
     * Register a gauge backed by a {@link ToDoubleFunction} on the supplied
     * object. Returns the object so callers can chain {@code Metrics.gauge(...)}
     * inside field declarations.
     */
    public static <T> T gauge(String name, T obj, ToDoubleFunction<T> valueFunction) {
        Gauge.builder(name, obj, valueFunction).register(registry);
        return obj;
    }

    /**
     * Convenience overload: register a gauge backed by a {@link Number} value.
     * Micrometer holds a weak reference, so callers must keep the returned
     * value alive.
     */
    public static <T extends Number> T gauge(String name, T value) {
        return registry.gauge(name, value);
    }
}
