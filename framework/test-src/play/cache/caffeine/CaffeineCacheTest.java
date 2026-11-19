package play.cache.caffeine;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.CacheStats;
import play.libs.Metrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

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
    public void aCacheIsMeasuredFromTheMomentItIsMade() {
        // PF-203: caches are made on first use, after MetricsPlugin's bindMetricsToRegistry
        io.micrometer.core.instrument.MeterRegistry previous = play.libs.Metrics.registry();
        SimpleMeterRegistry installed = new SimpleMeterRegistry();
        play.libs.Metrics.install(installed);
        try {
            Cache<String, String> cache = new CaffeineCacheProvider()
                    .create("made.late", CacheConfig.newBuilder().recordStats(true).build());
            cache.put("k", "v");

            assertThat(installed.find("cache.size").tag("cache", "made.late").gauge())
                    .as("measured without a bindMetricsToRegistry call").isNotNull();
            assertThat(installed.find("cache.size").tag("cache", "made.late").gauge().value()).isEqualTo(1.0);
        } finally {
            play.libs.Metrics.install(previous);
        }
    }

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

        // Caches created without recordStats get only the cache.size gauge (PF-223:
        // the provider registers it itself, as CaffeineCacheMetrics would). It still
        // proves each created cache was bound to the registry, which is what this asserts.
        assertThat(registry.find("cache.size").tag("cache", "alpha").meters()).isNotEmpty();
        assertThat(registry.find("cache.size").tag("cache", "beta").meters()).isNotEmpty();
    }

    /** PF-203's two ways a cache reaches the registry a start installs. */
    enum Binding {
        /** Made after the start installed its registry: {@code create} binds it. */
        MADE_AFTER_THE_START,
        /** Made before: the start binds it, as MetricsPlugin does through Caches.bindMetricsToRegistry. */
        MADE_BEFORE_THE_START
    }

    @ParameterizedTest
    @EnumSource(Binding.class)
    public void aCacheWithoutStatsGetsTheSizeGaugeAndNoWarning(Binding binding) {
        // PF-223: CaffeineCacheMetrics logged a WARN for every such cache at every start
        String name = "nostats." + binding;
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<String> warnings = new ArrayList<>();
        Cache<String, String> cache = capturingCaffeineCacheMetricsWarnings(warnings,
                () -> make(binding, registry, name, CacheConfig.newBuilder().build()));
        cache.put("k", "v");

        assertThat(warnings).isEmpty();
        assertThat(registry.find("cache.size").tag("cache", name).gauge().value()).isEqualTo(1.0);
        assertThat(Search.in(registry).tag("cache", name).meters())
                .extracting(m -> m.getId().getName()).containsExactly("cache.size");
        assertThat(cache.estimatedSize()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Binding.class)
    public void aCacheWithStatsKeepsCaffeineCacheMetricsFullSet(Binding binding) {
        String name = "stats." + binding;
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Cache<String, String> cache = make(binding, registry, name, CacheConfig.newBuilder().recordStats(true).build());
        cache.put("k", "v");
        cache.getIfPresent("k");
        cache.getIfPresent("absent");

        assertThat(registry.find("cache.gets").tag("cache", name).tag("result", "hit").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.find("cache.gets").tag("cache", name).tag("result", "miss").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.find("cache.puts").tag("cache", name).functionCounter()).isNotNull();
        assertThat(registry.find("cache.evictions").tag("cache", name).functionCounter()).isNotNull();
        assertThat(registry.find("cache.size").tag("cache", name).gauge().value()).isEqualTo(1.0);
        assertThat(cache.estimatedSize()).isEqualTo(1);
    }

    @Test
    public void withoutStatsTheGaugeIsTheOneCaffeineCacheMetricsRegisters() {
        // The reference is Micrometer's own binder on a cache without stats. It logs the
        // WARN, which also shows that the capture the test above relies on would see it.
        SimpleMeterRegistry reference = new SimpleMeterRegistry();
        List<String> warnings = new ArrayList<>();
        com.github.benmanes.caffeine.cache.Cache<Object, Object> referenceCache = Caffeine.newBuilder().build();
        capturingCaffeineCacheMetricsWarnings(warnings,
                () -> CaffeineCacheMetrics.monitor(reference, referenceCache, "same"));
        assertThat(warnings).singleElement().asString().contains("'same' is not recording statistics");

        SimpleMeterRegistry ours = new SimpleMeterRegistry();
        Cache<String, String> cache = make(Binding.MADE_AFTER_THE_START, ours, "same", CacheConfig.newBuilder().build());

        assertThat(describe(ours, "same")).isEqualTo(describe(reference, "same"));
        assertThat(cache.estimatedSize()).isEqualTo(referenceCache.estimatedSize());
    }

    private static Cache<String, String> make(Binding binding, MeterRegistry started, String name, CacheConfig config) {
        MeterRegistry previous = Metrics.registry();
        CaffeineCacheProvider provider = new CaffeineCacheProvider();
        try {
            if (binding == Binding.MADE_AFTER_THE_START) {
                Metrics.install(started);
                return provider.create(name, config);
            }
            Metrics.install(new SimpleMeterRegistry());
            Cache<String, String> cache = provider.create(name, config);
            Metrics.install(started);
            provider.bindMetricsToRegistry(started);
            return cache;
        } finally {
            Metrics.install(previous);
        }
    }

    /** Each meter of {@code cacheName}: name, tags, type, description and unit, in a stable order. */
    private static List<List<Object>> describe(MeterRegistry registry, String cacheName) {
        return Search.in(registry).tag("cache", cacheName).meters().stream()
                .map(Meter::getId)
                .sorted(Comparator.comparing(Meter.Id::getName))
                .map(id -> List.<Object>of(id.getName(), id.getTags(), id.getType(),
                        String.valueOf(id.getDescription()), String.valueOf(id.getBaseUnit())))
                .toList();
    }

    /** Run {@code action} and collect what CaffeineCacheMetrics logs at WARN or above meanwhile. */
    private static <T> T capturingCaffeineCacheMetricsWarnings(List<String> into, Supplier<T> action) {
        String loggerName = CaffeineCacheMetrics.class.getName();
        AbstractAppender appender = new AbstractAppender("PF-223", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                    into.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        Level previousLevel = LogManager.getLogger(loggerName).getLevel();
        Configurator.setLevel(loggerName, Level.WARN);
        org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName);
        logger.addAppender(appender);
        try {
            return action.get();
        } finally {
            logger.removeAppender(appender);
            Configurator.setLevel(loggerName, previousLevel);
            appender.stop();
        }
    }
}
