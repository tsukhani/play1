package play.cache.caffeine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.CacheStats;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-88 Caffeine provider tests. Covers the contract surface the framework
 * relies on — TTL eviction, max-size eviction, stats recording, and
 * Micrometer integration via the provider's {@code bindMetricsToRegistry}.
 *
 * <p>Constructed directly rather than going through {@link play.cache.Caches}
 * so each test gets a fresh provider — the registry-shared-state cases live
 * in {@link play.cache.CachesTest}.
 */
public class CaffeineCacheTest {

    @Test
    public void putAndGetIfPresentRoundtrip() {
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        Cache<String, String> cache = provider.create("rt", CacheConfig.newBuilder().build());

        cache.put("k", "v");
        assertThat(cache.getIfPresent("k")).isEqualTo("v");
        assertThat(cache.getIfPresent("missing")).isNull();
    }

    @Test
    public void getWithLoaderInvokesLoaderOnceOnMiss() {
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        Cache<String, Integer> cache = provider.create("loader", CacheConfig.newBuilder().build());
        int[] calls = {0};

        cache.get("k", k -> { calls[0]++; return 42; });
        cache.get("k", k -> { calls[0]++; return 99; });

        assertThat(calls[0]).isEqualTo(1).as("loader runs once; second get hits the cached value");
        assertThat(cache.getIfPresent("k")).isEqualTo(42);
    }

    @Test
    public void expireAfterWriteEvictsAfterTtl() throws Exception {
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        Cache<String, String> cache = provider.create("ttl",
                CacheConfig.newBuilder().expireAfterWrite(Duration.ofMillis(50)).build());

        cache.put("k", "v");
        assertThat(cache.getIfPresent("k")).isEqualTo("v");

        Thread.sleep(120);
        assertThat(cache.getIfPresent("k")).as("entry must expire after TTL elapses").isNull();
    }

    @Test
    public void maximumSizeBoundsEntryCount() {
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        Cache<Integer, Integer> cache = provider.create("size", CacheConfig.newBuilder().maximumSize(3).build());

        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
        }
        // Caffeine evicts asynchronously through its maintenance thread, so
        // estimatedSize() right after the puts can still report the unbounded
        // count. Force-drain pending eviction work via the native cache (only
        // accessible because this test is in the same package as CaffeineCache).
        ((CaffeineCache<Integer, Integer>) cache).nativeCache().cleanUp();

        assertThat(cache.estimatedSize()).as("maximum-size cap must hold once maintenance drains").isLessThanOrEqualTo(3L);
    }

    @Test
    public void recordStatsTracksHitsMissesAndLoads() {
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        Cache<String, String> cache = provider.create("stats", CacheConfig.newBuilder().recordStats(true).build());

        cache.put("k", "v");
        cache.getIfPresent("k");      // 1 hit
        cache.getIfPresent("k");      // 1 hit
        cache.getIfPresent("absent"); // 1 miss
        cache.get("loaded", k -> "X");// 1 miss + 1 load

        CacheStats s = cache.stats();
        assertThat(s.hitCount()).isEqualTo(2);
        assertThat(s.missCount()).isEqualTo(2);
        assertThat(s.loadCount()).isEqualTo(1);
        assertThat(s.hitRate()).isEqualTo(2.0 / 4.0);
    }

    @Test
    public void statsReturnsZeroSnapshotWhenRecordingDisabled() {
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        Cache<String, String> cache = provider.create("nostats", CacheConfig.newBuilder().build());
        cache.put("k", "v");
        cache.getIfPresent("k");

        CacheStats s = cache.stats();
        assertThat(s.hitCount()).isZero();
        assertThat(s.missCount()).isZero();
        assertThat(s.loadCount()).isZero();
        assertThat(s.evictionCount()).isZero();
    }

    @Test
    public void bindMetricsToRegistryAttachesEveryCreatedCache() {
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        provider.create("alpha", CacheConfig.newBuilder().build());
        provider.create("beta",  CacheConfig.newBuilder().build());

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.bindMetricsToRegistry(registry);

        assertThat(registry.find("cache.gets").tag("cache", "alpha").meters()).isNotEmpty();
        assertThat(registry.find("cache.gets").tag("cache", "beta").meters()).isNotEmpty();
    }
}
