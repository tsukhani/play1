package play.cache;

import play.Logger;
import play.libs.Time;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deprecated compatibility facade restoring the 1.x static
 * {@code play.cache.Cache} API on top of the typed cache registry (PF-133).
 *
 * <p>PF-88 replaced the old String/Object static facade with the typed
 * {@link Cache} interface obtained from {@link Caches#named(String, CacheConfig)}.
 * Application code written against the 1.x API
 * ({@code Cache.set(key, value, "10mn")}, {@code Cache.get(key)}, ...) no longer
 * compiled. This class re-exposes that surface so a 1.12-era app boots without a
 * source rewrite, mapping each call onto the new registry.
 *
 * <p><strong>This is a migration aid, not the supported API.</strong> New code
 * should obtain a typed {@link Cache} via {@link Caches#named(String, CacheConfig)}
 * and call {@link Cache#getIfPresent}, {@link Cache#get(Object, java.util.function.Function)},
 * {@link Cache#put}, and {@link Cache#invalidate} directly. The typed contract is
 * generic, dependency-inverted, and free of the serialization and atomicity
 * baggage the old facade carried over from its memcached origins.
 *
 * <h2>How it maps onto {@link Caches}</h2>
 * Caffeine eviction is per-cache, not per-entry, so — exactly as
 * {@code ActionInvoker.actionCache} and {@code FastTags.fragmentCache} do — each
 * distinct TTL string is backed by its own named cache
 * ({@code legacy.cache.<ttl>}) whose {@code expireAfterWrite} matches that TTL.
 * The old facade exposed a single flat keyspace, so {@link #get(String)} and
 * {@link #delete(String)} scan every TTL bucket created so far and
 * {@link #set(String, Object, String)} first evicts any prior entry for the key
 * (under any TTL) before writing, preserving the "a re-set replaces" semantics
 * of the single-store original.
 *
 * <h2>Deliberately unsupported</h2>
 * {@link #add}, {@link #replace}, {@link #incr}, and {@link #decr} have no
 * faithful mapping: the typed {@link Cache} contract offers no atomic
 * add-if-absent, replace-if-present, or atomic numeric mutation, and the old
 * facade's memcached-backed versions were genuinely atomic. Emulating them with
 * get-then-put would be racy and silently lie about atomicity, so they throw
 * {@link UnsupportedOperationException} rather than fake the semantics. Port such
 * call sites to {@link Cache#get(Object, java.util.function.Function)} or an
 * explicit {@code AtomicLong}.
 *
 * @deprecated since PF-133. Use {@link Caches#named(String, CacheConfig)} and the
 *             typed {@link Cache} contract instead.
 */
@Deprecated(since = "PF-133")
public final class LegacyCache {

    /** Cache-name prefix for the per-TTL buckets this facade creates. */
    private static final String NAME_PREFIX = "legacy.cache.";

    /**
     * TTL tokens for which a backing cache has been requested. We record the
     * tokens only — never the {@link Cache} references — so {@link #get}/
     * {@link #delete}/{@link #clear} know which bucket names to scan across the
     * flat keyspace the old facade presented. The live {@link Cache} for a token
     * is always re-resolved through {@link Caches#named} (an idempotent registry
     * lookup), so a full {@code Play.stop()} → {@code Play.start()} cycle — which
     * clears the registry and swaps the provider — never leaves us writing to or
     * reading from an orphaned cache from a prior lifecycle. A surviving token is
     * harmless: re-resolving its name just recreates an empty cache in the new
     * registry, matching the empty state {@code Caches.stop()} left behind. A
     * bounded set in practice — one entry per distinct duration string an app
     * passes to {@link #set}.
     */
    private static final Set<String> ttlTokens = ConcurrentHashMap.newKeySet();

    private LegacyCache() {}

    /**
     * Resolve the live backing cache for {@code ttl} (recording the token on
     * first use), mirroring the per-TTL naming used by {@code @CacheFor} and
     * {@code #{cache}}. Resolution goes through {@link Caches#named} on every
     * call so the returned {@link Cache} always belongs to the current registry,
     * never a stale instance from before a restart.
     */
    private static Cache<String, Object> bucket(String ttl) {
        ttlTokens.add(ttl);
        return Caches.named(NAME_PREFIX + ttl, configFor(ttl));
    }

    /**
     * Build the {@link CacheConfig} for a TTL token. The {@code expireAfterWrite}
     * matches that TTL; {@link Caches#named} ignores the config on all but the
     * first resolution of a given name, so rebuilding it per call is cheap and
     * keeps the live-registry resolution self-contained.
     */
    private static CacheConfig configFor(String ttl) {
        return CacheConfig.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Time.parseDuration(ttl)))
                .recordStats(true)
                .build();
    }

    /**
     * Set an element with an explicit expiration.
     *
     * @param key        element key
     * @param value      element value
     * @param expiration 1.x duration string, e.g. {@code 10s}, {@code 3mn}, {@code 8h}
     * @deprecated use {@link Caches#named(String, CacheConfig)} with
     *             {@link CacheConfig.Builder#expireAfterWrite(Duration)} and
     *             {@link Cache#put(Object, Object)}.
     */
    @Deprecated(since = "PF-133")
    public static void set(String key, Object value, String expiration) {
        // Single flat keyspace in 1.x: re-setting a key replaces it regardless
        // of its prior TTL bucket. Evict any stale copy before writing.
        deleteAcrossBuckets(key);
        bucket(normalizeTtl(expiration)).put(key, value);
    }

    /**
     * Set an element with the 1.x default expiration ({@link Time#parseDuration}
     * treats a null duration as 30 days — matched here faithfully).
     *
     * @param key   element key
     * @param value element value
     * @deprecated use {@link Caches#named(String, CacheConfig)} and {@link Cache#put(Object, Object)}.
     */
    @Deprecated(since = "PF-133")
    public static void set(String key, Object value) {
        set(key, value, null);
    }

    /**
     * Set an element and report whether it was cached. In-process Caffeine
     * writes are synchronous, so this is {@code true} unless the put throws.
     *
     * @return {@code true} if the value was cached, {@code false} on failure
     * @deprecated use {@link Caches#named(String, CacheConfig)} and {@link Cache#put(Object, Object)}.
     */
    @Deprecated(since = "PF-133")
    public static boolean safeSet(String key, Object value, String expiration) {
        try {
            set(key, value, expiration);
            return true;
        } catch (RuntimeException e) {
            Logger.warn(e, "LegacyCache.safeSet failed for key %s", key);
            return false;
        }
    }

    /**
     * Retrieve an object across the flat keyspace.
     *
     * @return the cached value, or {@code null} on miss
     * @deprecated use {@link Caches#named(String, CacheConfig)} and {@link Cache#getIfPresent(Object)}.
     */
    @Deprecated(since = "PF-133")
    public static Object get(String key) {
        for (String ttl : ttlTokens) {
            Object value = bucket(ttl).getIfPresent(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Retrieve an object cast to the requested type.
     *
     * @return the cached value, or {@code null} on miss
     * @deprecated use {@link Caches#named(String, CacheConfig)} with a typed
     *             {@code Cache<String, T>} and {@link Cache#getIfPresent(Object)}.
     */
    @Deprecated(since = "PF-133")
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, Class<T> clazz) {
        return (T) get(key);
    }

    /**
     * Delete an element from the cache.
     *
     * @deprecated use {@link Caches#named(String, CacheConfig)} and {@link Cache#invalidate(Object)}.
     */
    @Deprecated(since = "PF-133")
    public static void delete(String key) {
        deleteAcrossBuckets(key);
    }

    /**
     * Delete an element and report success. In-process Caffeine invalidation is
     * synchronous, so this is {@code true} unless invalidation throws.
     *
     * @return {@code true} if the delete completed, {@code false} on failure
     * @deprecated use {@link Caches#named(String, CacheConfig)} and {@link Cache#invalidate(Object)}.
     */
    @Deprecated(since = "PF-133")
    public static boolean safeDelete(String key) {
        try {
            deleteAcrossBuckets(key);
            return true;
        } catch (RuntimeException e) {
            Logger.warn(e, "LegacyCache.safeDelete failed for key %s", key);
            return false;
        }
    }

    /**
     * Clear every entry this facade created. Only touches the {@code legacy.cache.*}
     * buckets — caches created directly via {@link Caches#named} are untouched,
     * unlike the global wipe the 1.x {@code Cache.clear()} performed against the
     * single shared store.
     *
     * @deprecated use {@link Caches#named(String, CacheConfig)} and {@link Cache#invalidateAll()}.
     */
    @Deprecated(since = "PF-133")
    public static void clear() {
        for (String ttl : ttlTokens) {
            bucket(ttl).invalidateAll();
        }
    }

    /**
     * Unsupported: the typed {@link Cache} contract has no atomic add-if-absent
     * and the 1.x memcached-backed {@code add} was genuinely atomic. Faking it
     * with get-then-put would be racy.
     *
     * @throws UnsupportedOperationException always
     * @deprecated no replacement; use {@link Cache#get(Object, java.util.function.Function)}
     *             if you need load-on-miss semantics.
     */
    @Deprecated(since = "PF-133")
    public static void add(String key, Object value, String expiration) {
        throw unsupported("add");
    }

    /**
     * Unsupported: see {@link #add}. The typed contract has no replace-if-present.
     *
     * @throws UnsupportedOperationException always
     * @deprecated no replacement.
     */
    @Deprecated(since = "PF-133")
    public static void replace(String key, Object value, String expiration) {
        throw unsupported("replace");
    }

    /**
     * Unsupported: the typed {@link Cache} contract has no atomic numeric
     * increment. Use an {@link java.util.concurrent.atomic.AtomicLong}.
     *
     * @throws UnsupportedOperationException always
     * @deprecated no replacement.
     */
    @Deprecated(since = "PF-133")
    public static long incr(String key, int by) {
        throw unsupported("incr");
    }

    /**
     * Unsupported: see {@link #incr}.
     *
     * @throws UnsupportedOperationException always
     * @deprecated no replacement.
     */
    @Deprecated(since = "PF-133")
    public static long decr(String key, int by) {
        throw unsupported("decr");
    }

    private static void deleteAcrossBuckets(String key) {
        for (String ttl : ttlTokens) {
            bucket(ttl).invalidate(key);
        }
    }

    /**
     * 1.x {@link Time#parseDuration} accepts {@code null} (→ 30 days). Caffeine's
     * cache name must be stable, so collapse null/blank to a canonical token
     * that still parses to the same 30-day TTL.
     */
    private static String normalizeTtl(String expiration) {
        if (expiration == null || expiration.isBlank()) {
            return "30d";
        }
        return expiration;
    }

    private static UnsupportedOperationException unsupported(String op) {
        return new UnsupportedOperationException(
                "LegacyCache." + op + " is not supported: the typed play.cache.Cache contract (PF-88) "
                + "offers no atomic add/replace/incr/decr. Port this call site to "
                + "Cache.get(key, loader) or an explicit AtomicLong. See Caches.named(...).");
    }
}
