package play.cache;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * SPI for cache backends (PF-88). Implementations are discovered via
 * {@link java.util.ServiceLoader} from
 * {@code META-INF/services/play.cache.CacheProvider}.
 *
 * <p>The framework ships one provider — {@code play.cache.caffeine.CaffeineCacheProvider}
 * (name {@code "caffeine"}). Apps that want a different backend (EhCache,
 * a tiered Caffeine + disk store, a no-op for tests) register their own
 * implementation via the same SPI mechanism. {@link Caches} resolves the
 * provider at boot: single → use it; multiple → select via the
 * {@code cache.provider} property in {@code application.conf}; zero →
 * {@link play.exceptions.ConfigurationException} fail-fast.
 *
 * <p>Implementations must be safe for concurrent {@link #create} calls.
 * {@link Caches} additionally guarantees one cache per name via
 * {@code computeIfAbsent}, so providers can assume a name is stable for
 * the lifetime of the app.
 */
public interface CacheProvider {

    /**
     * Stable identifier used by {@code cache.provider} config to select
     * among multiple registered providers. Convention: lowercase, no spaces
     * (e.g. {@code "caffeine"}, {@code "ehcache"}, {@code "noop"}).
     */
    String name();

    /**
     * Build a {@link Cache} for the supplied logical name with the given
     * configuration. Called by {@link Caches#named} on the first request
     * for each name; cached thereafter.
     */
    <K, V> Cache<K, V> create(String cacheName, CacheConfig config);

    /**
     * Re-attach Micrometer metrics for every cache this provider has
     * created against {@code registry}. Called by
     * {@code play.plugins.MetricsPlugin.onApplicationStart} after the
     * Prometheus registry is installed — same dev-mode hot-reload story as
     * HikariCP's tracker rebind: caches created before the registry was
     * swapped need to be re-attached, otherwise metrics emit to the old
     * (closed) registry.
     *
     * <p>Providers without per-cache metrics may implement this as a no-op.
     */
    default void bindMetricsToRegistry(MeterRegistry registry) {}

    /**
     * Release any provider-held resources. Called by {@link Caches#stop()}
     * during {@code Play.stop()}. Implementations should drain async
     * workers, close native handles, and forget any cached references.
     */
    void stop();
}
