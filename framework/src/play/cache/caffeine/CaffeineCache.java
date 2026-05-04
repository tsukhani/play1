package play.cache.caffeine;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import play.cache.Cache;

import java.util.function.Function;

/**
 * Caffeine-backed {@link Cache} implementation (PF-88). Thin delegating
 * wrapper around {@link com.github.benmanes.caffeine.cache.Cache} — the
 * heavy lifting (W-TinyLFU eviction, statistics, async refresh) all stays
 * in Caffeine. This class exists to decouple the framework's contract from
 * the concrete library so {@code play.cache} stays Caffeine-free.
 *
 * <p>Constructed by {@link CaffeineCacheProvider#create(String, play.cache.CacheConfig)};
 * never instantiated directly by application code.
 */
final class CaffeineCache<K, V> implements Cache<K, V> {

    private final com.github.benmanes.caffeine.cache.Cache<K, V> delegate;

    CaffeineCache(com.github.benmanes.caffeine.cache.Cache<K, V> delegate) {
        this.delegate = delegate;
    }

    /**
     * Package-private accessor for the underlying Caffeine cache.
     * {@link CaffeineCacheProvider#bindMetricsToRegistry} uses it to attach
     * Micrometer's {@code CaffeineCacheMetrics} binder. No public callers.
     */
    com.github.benmanes.caffeine.cache.Cache<K, V> nativeCache() {
        return delegate;
    }

    @Override
    public V getIfPresent(K key) {
        return delegate.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        return delegate.get(key, loader);
    }

    @Override
    public void put(K key, V value) {
        delegate.put(key, value);
    }

    @Override
    public void invalidate(K key) {
        delegate.invalidate(key);
    }

    @Override
    public void invalidateAll() {
        delegate.invalidateAll();
    }

    @Override
    public long estimatedSize() {
        return delegate.estimatedSize();
    }

    @Override
    public play.cache.CacheStats stats() {
        CacheStats s = delegate.stats();
        return new play.cache.CacheStats(s.hitCount(), s.missCount(), s.loadCount(), s.evictionCount());
    }
}
