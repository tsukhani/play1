package play.cache.caffeine;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import play.Logger;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.CacheProvider;
import play.libs.Metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link CacheProvider} for the framework (PF-88). Backs each named
 * cache with a {@link com.github.benmanes.caffeine.cache.Cache} configured
 * via Caffeine's W-TinyLFU eviction. Single-jar, no native bits, no async
 * scheduler threads under the framework's control — the same properties
 * that made the previous {@code CaffeineImpl} a good fit for the in-process
 * cache.
 *
 * <p>Registered via {@code META-INF/services/play.cache.CacheProvider}.
 * Apps that want a different backend can drop in their own provider via
 * the same SPI mechanism and select it with {@code cache.provider} in
 * {@code application.conf}.
 *
 * <p>Each cache binds its meters to the installed registry when it is made
 * (PF-203: before, only {@link #bindMetricsToRegistry} bound them, once per
 * start and before any cache made on first use existed, so those were never
 * measured). The name-keyed map lets
 * {@link #bindMetricsToRegistry(MeterRegistry)} bind the caches made before a
 * start installed its registry. None outlives a reload ({@link #stop()} drops
 * them), so their meters go with the registry they were made on.
 *
 * <p>A cache that records statistics gets {@link CaffeineCacheMetrics}' full
 * set. One that does not gets only the {@code cache.size} gauge, registered
 * here as {@link CaffeineCacheMetrics} registers it (PF-223): for such a cache
 * that binder registers nothing else and logs a WARN, once per cache at every
 * start. Recording statistics for every cache would also silence it, but would
 * add the stats counters to every lookup of a cache that did not ask for them.
 */
public final class CaffeineCacheProvider implements CacheProvider {

    private final ConcurrentMap<String, CaffeineCache<?, ?>> caches = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "caffeine";
    }

    @Override
    public <K, V> Cache<K, V> create(String cacheName, CacheConfig config) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        config.expireAfterWrite().ifPresent(builder::expireAfterWrite);
        config.expireAfterAccess().ifPresent(builder::expireAfterAccess);
        if (config.maximumSize() >= 0) {
            builder.maximumSize(config.maximumSize());
        }
        if (config.recordStats()) {
            builder.recordStats();
        }
        com.github.benmanes.caffeine.cache.Cache<K, V> nativeCache = builder.build();
        CaffeineCache<K, V> wrapped = new CaffeineCache<>(nativeCache);
        caches.put(cacheName, wrapped);
        bind(Metrics.registry(), cacheName, wrapped);
        return wrapped;
    }

    /**
     * Re-attach the meters of every created cache against
     * {@code registry}. Each cache surfaces under the {@code cache} tag with
     * its registered name so dashboards can drill in by feature
     * ({@code cache="play.actions"}, {@code cache="play.fragments"},
     * {@code cache="agents"}, ...).
     */
    @Override
    public void bindMetricsToRegistry(MeterRegistry registry) {
        for (Map.Entry<String, CaffeineCache<?, ?>> e : caches.entrySet()) {
            bind(registry, e.getKey(), e.getValue());
        }
    }

    private static void bind(MeterRegistry registry, String cacheName, CaffeineCache<?, ?> cache) {
        try {
            com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache = cache.nativeCache();
            if (nativeCache.policy().isRecordingStats()) {
                CaffeineCacheMetrics.monitor(registry, nativeCache, cacheName);
            } else {
                Gauge.builder("cache.size", nativeCache, com.github.benmanes.caffeine.cache.Cache::estimatedSize)
                        .tag("cache", cacheName)
                        .description("The number of entries in this cache. This may be an approximation, depending on the type of cache.")
                        .register(registry);
            }
        } catch (Throwable t) {
            Logger.warn(t, "CaffeineCacheProvider -> failed to bind metrics for cache=%s", cacheName);
        }
    }

    @Override
    public void stop() {
        for (CaffeineCache<?, ?> c : caches.values()) {
            c.invalidateAll();
        }
        caches.clear();
    }
}
