package play.cache;

/**
 * Immutable snapshot of a {@link Cache}'s lifetime counters (PF-88).
 *
 * <p>Returned by {@link Cache#stats()}. Caches built without
 * {@link CacheConfig.Builder#recordStats(boolean)} return a zero-valued
 * snapshot rather than throwing, so callers don't have to check.
 *
 * @param hitCount      reads that returned a non-null cached value
 * @param missCount     reads that returned {@code null} (entry absent or expired)
 * @param loadCount     loader invocations from {@link Cache#get(Object, java.util.function.Function)}
 * @param evictionCount entries removed by the cache itself (size or TTL), excluding explicit invalidations
 */
public record CacheStats(long hitCount, long missCount, long loadCount, long evictionCount) {

    /**
     * Hit rate over total reads. Returns 1.0 when there have been no reads
     * (so the metric is well-defined for a cold cache; treat with the same
     * skepticism as any rate over a small sample).
     */
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 1.0 : (double) hitCount / total;
    }
}
