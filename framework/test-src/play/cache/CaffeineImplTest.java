package play.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class CaffeineImplTest {

    @Test
    public void verifyThatTTLSurvivesIncrDecr() throws Exception {
        // PF-25 regression: every write (incr/decr included) resets the TTL clock
        // to "now + the entry's own TTL", matching the EhCache 2.x behavior the
        // historical test exercised. Without this, incr/decr would either drop
        // the TTL (entry never expires) or shorten it to currentDuration (entry
        // expires sooner than expected).
        CaffeineImpl cache = CaffeineImpl.newInstance();
        cache.clear();

        String key = "CaffeineImplTest_verifyThatTTLSurvivesIncrDecr";

        int expiration = 1;

        cache.add(key, 1, expiration);
        Thread.sleep(100);
        cache.incr(key, 4);

        Thread.sleep(100);
        cache.decr(key, 3);

        Thread.sleep(950);
        assertThat(cache.get(key)).isEqualTo(2L);

        // Now make sure it disappears after the 1 sec + 200 ms.
        Thread.sleep(150);
        assertThat(cache.get(key)).isNull();
    }

    @Test
    public void bindMetricsRegistersCacheMetersOnRegistry() {
        // PF-86: bindMetrics attaches Caffeine's hit/miss/eviction/load gauges
        // and counters to the supplied MeterRegistry. Verify against a
        // SimpleMeterRegistry — same surface area as the production
        // PrometheusMeterRegistry from MetricsPlugin's perspective.
        CaffeineImpl cache = CaffeineImpl.newInstance();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        cache.bindMetrics(registry);

        // Touch the cache so cache.gets / cache.size have a sample to publish.
        cache.set("k", "v", 60);
        Object hit = cache.get("k");
        Object miss = cache.get("nonexistent");
        assertThat(hit).isEqualTo("v");
        assertThat(miss).isNull();

        // Caffeine's binder names follow the cache.* convention with a "cache"
        // tag scoping each meter to a named cache. PF-86 names the framework
        // cache "play_cache".
        assertThat(registry.find("cache.size").tag("cache", "play_cache").gauge())
                .as("cache.size gauge present").isNotNull();
        assertThat(registry.find("cache.gets").tag("cache", "play_cache").meters())
                .as("cache.gets meters present").isNotEmpty();
    }
}
