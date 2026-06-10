package play.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * PF-133: exercises the deprecated {@link LegacyCache} compatibility facade
 * against an in-process {@link CacheProvider} (same {@code ConcurrentHashMap}
 * test backend pattern as {@link CachesTest}), so no real Caffeine backend or
 * {@code Play.start()} is required. The map backend ignores TTL — TTL honoring
 * is covered against the real Caffeine cache in {@code CaffeineCacheTest}; here
 * we only assert the facade's flat-keyspace set/get/delete/typed-get mapping
 * and that the genuinely-unsupported ops fail loudly.
 */
public class LegacyCacheTest {

    @BeforeEach
    public void installProvider() {
        Caches.installProvider(new MapProvider());
    }

    @AfterEach
    public void resetState() {
        LegacyCache.clear();
        Caches.stop();
    }

    @Test
    public void setThenGetReturnsValue() {
        LegacyCache.set("k", "v", "10s");
        assertThat(LegacyCache.get("k")).isEqualTo("v");
    }

    @Test
    public void deleteRemovesValue() {
        LegacyCache.set("k", "v", "10s");
        LegacyCache.delete("k");
        assertThat(LegacyCache.get("k")).isNull();
    }

    @Test
    public void typedGetReturnsTypedValue() {
        LegacyCache.set("k", "v", "10s");
        String value = LegacyCache.get("k", String.class);
        assertThat(value).isEqualTo("v");
    }

    @Test
    public void getOnMissReturnsNull() {
        assertThat(LegacyCache.get("absent")).isNull();
    }

    @Test
    public void setWithoutDurationStoresValue() {
        LegacyCache.set("k", "v");
        assertThat(LegacyCache.get("k")).isEqualTo("v");
    }

    @Test
    public void resettingAKeyUnderADifferentTtlReplacesNotDuplicates() {
        LegacyCache.set("k", "first", "10s");
        LegacyCache.set("k", "second", "1h");
        assertThat(LegacyCache.get("k")).isEqualTo("second");
    }

    @Test
    public void safeSetAndSafeDeleteReportSuccess() {
        assertThat(LegacyCache.safeSet("k", "v", "10s")).isTrue();
        assertThat(LegacyCache.get("k")).isEqualTo("v");
        assertThat(LegacyCache.safeDelete("k")).isTrue();
        assertThat(LegacyCache.get("k")).isNull();
    }

    @Test
    public void clearEmptiesEveryBucket() {
        LegacyCache.set("a", "1", "10s");
        LegacyCache.set("b", "2", "1h");
        LegacyCache.clear();
        assertThat(LegacyCache.get("a")).isNull();
        assertThat(LegacyCache.get("b")).isNull();
    }

    /**
     * Regression for the {@code bucketsByTtl}-caching bug: across a full
     * registry restart ({@link Caches#stop()} then a fresh provider install,
     * exactly as {@link #installProvider()} does on boot — mirroring
     * {@code Play.stop()} → {@code Play.start()}), the facade must re-resolve its
     * buckets through {@link Caches#named} against the LIVE registry rather than
     * reuse orphaned {@link Cache} instances from the prior lifecycle. The
     * pre-restart value is gone (stop cleared the registry), and a subsequent
     * set/get operates against the new provider.
     */
    @Test
    public void setGetReResolvesAgainstLiveRegistryAfterRestart() {
        LegacyCache.set("k", "before", "10s");
        assertThat(LegacyCache.get("k")).isEqualTo("before");

        // Simulate Play.stop() then Play.start(): drop the registry and provider,
        // then re-install a brand-new provider (same harness as @BeforeEach).
        Caches.stop();
        Caches.installProvider(new MapProvider());

        // The pre-restart value lived in a cache the stop() cleared — it's gone.
        assertThat(LegacyCache.get("k")).isNull();

        // A new write must reach the LIVE registry. With the old bucketsByTtl
        // cache this would write into the orphaned (no-longer-registered) cache,
        // so this set/get round-trip detects the bug.
        LegacyCache.set("k", "after", "10s");
        assertThat(LegacyCache.get("k")).isEqualTo("after");

        // And it really is in the current registry: Caches.named for the same
        // bucket name returns the instance the facade just wrote to.
        Cache<String, Object> live = Caches.named("legacy.cache.10s",
                CacheConfig.newBuilder().build());
        assertThat(live.getIfPresent("k")).isEqualTo("after");
    }

    @Test
    public void unsupportedOpsThrow() {
        assertThat(catchThrowable(() -> LegacyCache.add("k", "v", "10s")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(catchThrowable(() -> LegacyCache.replace("k", "v", "10s")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(catchThrowable(() -> LegacyCache.incr("k", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(catchThrowable(() -> LegacyCache.decr("k", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * In-process {@link CacheProvider} backed by {@link ConcurrentHashMap},
     * mirroring {@code CachesTest.MapProvider}. Ignores TTL/maximumSize.
     */
    static class MapProvider implements CacheProvider {
        @Override public String name() { return "map-test"; }
        @Override public <K, V> Cache<K, V> create(String cacheName, CacheConfig config) {
            return new MapCache<>();
        }
        @Override public void stop() {}
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
}
