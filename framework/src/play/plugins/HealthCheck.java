package play.plugins;

/**
 * SPI for application- and plugin-provided readiness probes (PF-11).
 *
 * <p>Implementations are registered via {@link HealthCheckPlugin#register(HealthCheck)}
 * and aggregated by {@code /@health/ready}: 200 if every check returns {@link Status#UP},
 * 503 if any returns {@link Status#DOWN}. {@code /@health/live} does not consult these
 * checks — it returns 200 unconditionally as long as the JVM is running.</p>
 *
 * <p>{@link #check()} is invoked synchronously on the request thread and should be
 * cheap. Implementations must not throw — return {@link Status#DOWN} on failure.</p>
 */
public interface HealthCheck {

    /**
     * Stable identifier surfaced in the JSON response (e.g. {@code "db"}, {@code "cache"}).
     * Should be unique across registered checks; duplicates are not deduplicated.
     */
    String name();

    /**
     * Probe the underlying resource and report status.
     */
    Status check();

    enum Status { UP, DOWN }
}
