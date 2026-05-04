package play.cache;

import java.util.function.Function;

/**
 * Typed runtime cache contract (PF-88). Replaces the legacy String/Object
 * static facade with a generic, dependency-inverted interface — concrete
 * implementations live in their own packages and are loaded via Java's
 * {@link java.util.ServiceLoader} through {@link CacheProvider}.
 *
 * <p>Obtain instances via {@link Caches#named(String, CacheConfig)} — the
 * registry guarantees one cache per name across the JVM. Direct
 * construction is intentionally not part of the contract.
 *
 * <p>Implementations must be safe for concurrent access from many threads.
 */
public interface Cache<K, V> {

    /**
     * Returns the value cached under {@code key}, or {@code null} if absent
     * or expired. Reads do not extend access-based expiration (see
     * {@link CacheConfig#expireAfterAccess()} for that).
     */
    V getIfPresent(K key);

    /**
     * Returns the value cached under {@code key}, computing and storing it
     * via {@code loader} on miss. The loader is invoked at most once per
     * concurrent miss for the same key — equivalent to atomic
     * {@code computeIfAbsent} on a {@link java.util.concurrent.ConcurrentMap}.
     * If the loader returns {@code null}, no entry is stored and {@code null}
     * is returned.
     */
    V get(K key, Function<? super K, ? extends V> loader);

    /**
     * Associates {@code value} with {@code key}, replacing any existing
     * entry. Resets the entry's TTL clock per the cache's
     * {@link CacheConfig#expireAfterWrite()}.
     */
    void put(K key, V value);

    /**
     * Removes the entry for {@code key} if present.
     */
    void invalidate(K key);

    /**
     * Removes all entries. Implementations should release the underlying
     * storage capacity opportunistically.
     */
    void invalidateAll();

    /**
     * Returns an estimate of the number of entries currently held. Reflects
     * pending eviction work and may transiently overcount; intended for
     * monitoring, not for control flow.
     */
    long estimatedSize();

    /**
     * Returns a snapshot of the cache's lifetime statistics. Returns a
     * zero-valued snapshot if the cache was built with
     * {@link CacheConfig.Builder#recordStats(boolean)} unset or false.
     */
    CacheStats stats();
}
