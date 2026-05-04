package play.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-88 registry/lookup tests. Provider resolution paths (single, multi,
 * none) live in {@link CachesProviderResolutionTest} because they need a
 * fresh {@link Caches} state per test; the cases here all share an
 * installed test provider.
 */
public class CachesTest {

    @AfterEach
    public void resetState() {
        Caches.stop();
    }

    @Test
    public void namedReturnsTheSameInstancePerName() {
        Caches.installProvider(new MapProvider());
        Cache<String, String> a = Caches.named("alpha", CacheConfig.newBuilder().build());
        Cache<String, String> b = Caches.named("alpha", CacheConfig.newBuilder().build());
        Cache<String, String> c = Caches.named("beta",  CacheConfig.newBuilder().build());

        assertThat(a).isSameAs(b)
                .as("Caches.named is idempotent per name (computeIfAbsent semantics)");
        assertThat(c).isNotSameAs(a)
                .as("different names must yield different cache instances");
    }

    @Test
    public void invalidateAllEmptiesEveryCache() {
        Caches.installProvider(new MapProvider());
        Cache<String, String> a = Caches.named("a", CacheConfig.newBuilder().build());
        Cache<String, String> b = Caches.named("b", CacheConfig.newBuilder().build());
        a.put("k", "v");
        b.put("k", "v");

        Caches.invalidateAll();

        assertThat(a.getIfPresent("k")).isNull();
        assertThat(b.getIfPresent("k")).isNull();
    }

    @Test
    public void namedBeforeInitFailsLoudly() {
        Caches.stop();  // ensure no provider is installed
        assertThat(catching(() -> Caches.named("x", CacheConfig.newBuilder().build())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Caches.init()");
    }

    @Test
    public void installProviderStopsThePreviousProvider() {
        TrackingProvider first = new TrackingProvider();
        Caches.installProvider(first);
        Caches.installProvider(new MapProvider());

        assertThat(first.stopped).isTrue()
                .as("installing a new provider must stop the previous one to release its resources");
    }

    /**
     * Minimal in-process {@link CacheProvider} backed by
     * {@link ConcurrentHashMap}. Doesn't honor TTL or maximumSize — those
     * are exercised against the real {@link play.cache.caffeine.CaffeineCache}
     * in {@code CaffeineCacheTest}. This provider only exists so registry
     * behavior (idempotency, lifecycle, invalidateAll) can be tested in
     * isolation.
     */
    static class MapProvider implements CacheProvider {
        @Override public String name() { return "map-test"; }
        @Override public <K, V> Cache<K, V> create(String cacheName, CacheConfig config) {
            return new MapCache<>();
        }
        @Override public void stop() {}
    }

    static class TrackingProvider extends MapProvider {
        boolean stopped = false;
        @Override public void stop() { stopped = true; }
    }

    static class MapCache<K, V> implements Cache<K, V> {
        private final ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();
        @Override public V getIfPresent(K key) { return map.get(key); }
        @Override public V get(K key, Function<? super K, ? extends V> loader) { return map.computeIfAbsent(key, loader); }
        @Override public void put(K key, V value) { map.put(key, value); }
        @Override public void invalidate(K key) { map.remove(key); }
        @Override public void invalidateAll() { map.clear(); }
        @Override public long estimatedSize() { return map.size(); }
        @Override public CacheStats stats() { return new CacheStats(0, 0, 0, 0); }
    }

    private static Throwable catching(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }
}
