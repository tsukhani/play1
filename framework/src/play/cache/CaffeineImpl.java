package play.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import play.Logger;

/**
 * Caffeine-backed implementation of the Play in-process cache. Replaces the
 * historical EhCache 2.x backend with a single-jar high-performance cache
 * using W-TinyLFU eviction.
 *
 * <p>Per-entry TTL (in seconds, matching the {@link CacheImpl} contract) is
 * carried in a wrapper {@link Entry} record and read by {@link Expiry} on each
 * write. Reads do not extend expiration. An expiration argument of {@code 0}
 * (or negative) means indefinite, mapped to {@link Long#MAX_VALUE} nanos.
 *
 * <p>{@link #incr} and {@link #decr} use Caffeine's atomic
 * {@code computeIfPresent}, which closes a read-modify-write race that existed
 * in the historical EhCache 2.x implementation under concurrent writers.
 */
public class CaffeineImpl implements CacheImpl {

    private static CaffeineImpl uniqueInstance;

    private record Entry(Object value, long ttlNanos) {}

    private final Cache<String, Entry> cache;

    private CaffeineImpl() {
        this.cache = Caffeine.newBuilder()
            .expireAfter(new Expiry<String, Entry>() {
                @Override public long expireAfterCreate(String key, Entry e, long currentTime) {
                    return e.ttlNanos;
                }
                @Override public long expireAfterUpdate(String key, Entry e, long currentTime, long currentDuration) {
                    // Match EhCache 2.x semantics: every write resets the clock to
                    // "now + the entry's own TTL", not the remaining duration of the
                    // prior entry. The incr/decr TTL-survives test relies on this.
                    return e.ttlNanos;
                }
                @Override public long expireAfterRead(String key, Entry e, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();
    }

    public static CaffeineImpl getInstance() {
        return uniqueInstance;
    }

    public static CaffeineImpl newInstance() {
        uniqueInstance = new CaffeineImpl();
        return uniqueInstance;
    }

    /**
     * PF-86: bind this cache's hit/miss/eviction/load metrics to the supplied
     * {@link MeterRegistry}. Called from {@code MetricsPlugin.onApplicationStart()}
     * after the framework's Prometheus registry is installed — a Cache.init()-time
     * binding wouldn't work because Cache.init() runs in {@code Play.start()}
     * before the plugin lifecycle, when {@code Metrics.registry()} still points at
     * the {@code SimpleMeterRegistry} default. Binding deferred to plugin start
     * ensures the live Prometheus registry receives every metric.
     *
     * <p>Not safe to call multiple times against the same registry —
     * {@link CaffeineCacheMetrics#monitor} registers fresh gauges on every
     * invocation, so a re-bind would duplicate the meters under the same name.
     * The plugin guards against that by binding exactly once per
     * {@code onApplicationStart}, and the registry itself is rebuilt on stop.
     */
    public void bindMetrics(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, cache, "play_cache");
    }

    private static long ttlNanos(int seconds) {
        return seconds <= 0 ? Long.MAX_VALUE : TimeUnit.SECONDS.toNanos(seconds);
    }

    @Override
    public void add(String key, Object value, int expiration) {
        cache.asMap().putIfAbsent(key, new Entry(value, ttlNanos(expiration)));
    }

    @Override
    public boolean safeAdd(String key, Object value, int expiration) {
        try {
            add(key, value, expiration);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void set(String key, Object value, int expiration) {
        cache.put(key, new Entry(value, ttlNanos(expiration)));
    }

    @Override
    public boolean safeSet(String key, Object value, int expiration) {
        try {
            set(key, value, expiration);
            return true;
        } catch (Exception e) {
            Logger.error(e.toString());
            return false;
        }
    }

    @Override
    public void replace(String key, Object value, int expiration) {
        cache.asMap().replace(key, new Entry(value, ttlNanos(expiration)));
    }

    @Override
    public boolean safeReplace(String key, Object value, int expiration) {
        try {
            replace(key, value, expiration);
            return true;
        } catch (Exception e) {
            Logger.error(e.toString());
            return false;
        }
    }

    @Override
    public Object get(String key) {
        Entry e = cache.getIfPresent(key);
        return e == null ? null : e.value;
    }

    @Override
    public Map<String, Object> get(String[] keys) {
        Map<String, Object> result = new HashMap<>(keys.length);
        for (String key : keys) {
            result.put(key, get(key));
        }
        return result;
    }

    @Override
    public long incr(String key, int by) {
        long[] result = {-1};
        cache.asMap().computeIfPresent(key, (k, e) -> {
            long newValue = ((Number) e.value).longValue() + by;
            result[0] = newValue;
            return new Entry(newValue, e.ttlNanos);
        });
        return result[0];
    }

    @Override
    public long decr(String key, int by) {
        long[] result = {-1};
        cache.asMap().computeIfPresent(key, (k, e) -> {
            long newValue = ((Number) e.value).longValue() - by;
            result[0] = newValue;
            return new Entry(newValue, e.ttlNanos);
        });
        return result[0];
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public void delete(String key) {
        cache.invalidate(key);
    }

    @Override
    public boolean safeDelete(String key) {
        try {
            delete(key);
            return true;
        } catch (Exception e) {
            Logger.error(e.toString());
            return false;
        }
    }

    @Override
    public void stop() {
        cache.invalidateAll();
        cache.cleanUp();
    }
}
