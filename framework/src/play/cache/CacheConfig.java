package play.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Immutable configuration for a {@link Cache} (PF-88). Backend-neutral —
 * each {@link CacheProvider} maps these fields to its own native equivalents
 * and silently ignores anything it cannot honor (a logging-only backend, for
 * example, would treat all eviction settings as no-ops).
 *
 * <p>Construct via {@link #newBuilder()}. All fields are optional; the
 * defaults produce an unbounded cache with no expiration and no stats
 * collection.
 */
public final class CacheConfig {

    private final Duration expireAfterWrite;
    private final Duration expireAfterAccess;
    private final long maximumSize;
    private final boolean recordStats;

    private CacheConfig(Builder b) {
        this.expireAfterWrite = b.expireAfterWrite;
        this.expireAfterAccess = b.expireAfterAccess;
        this.maximumSize = b.maximumSize;
        this.recordStats = b.recordStats;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    /** TTL relative to the most recent write. Empty = no write-based expiration. */
    public Optional<Duration> expireAfterWrite() {
        return Optional.ofNullable(expireAfterWrite);
    }

    /** TTL relative to the most recent read or write. Empty = no access-based expiration. */
    public Optional<Duration> expireAfterAccess() {
        return Optional.ofNullable(expireAfterAccess);
    }

    /** Max entry count before eviction kicks in. {@code -1} = unbounded. */
    public long maximumSize() {
        return maximumSize;
    }

    /** True if the provider should record hit/miss/load/eviction counts. */
    public boolean recordStats() {
        return recordStats;
    }

    public static final class Builder {
        private Duration expireAfterWrite;
        private Duration expireAfterAccess;
        private long maximumSize = -1;
        private boolean recordStats = false;

        private Builder() {}

        public Builder expireAfterWrite(Duration d) {
            this.expireAfterWrite = d;
            return this;
        }

        public Builder expireAfterAccess(Duration d) {
            this.expireAfterAccess = d;
            return this;
        }

        public Builder maximumSize(long n) {
            this.maximumSize = n;
            return this;
        }

        public Builder recordStats(boolean v) {
            this.recordStats = v;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(this);
        }
    }
}
