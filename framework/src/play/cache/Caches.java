package play.cache;

import io.micrometer.core.instrument.MeterRegistry;
import play.Logger;
import play.Play;
import play.exceptions.ConfigurationException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Public registry / entry point for the typed cache contract (PF-88).
 *
 * <p>Resolves a single {@link CacheProvider} at boot via
 * {@link ServiceLoader}; selection rule:
 * <ul>
 *   <li>Zero providers on the classpath → {@link ConfigurationException} at
 *       {@link #init()}. Caffeine ships with the framework so this only
 *       happens if someone explicitly removes it.</li>
 *   <li>One provider → use it. The default state.</li>
 *   <li>Multiple providers → require {@code cache.provider=<name>} in
 *       {@code application.conf}. The selected provider's {@link CacheProvider#name()}
 *       must match exactly. No silent first-wins fallback.</li>
 * </ul>
 *
 * <p>Application code obtains caches via {@link #named(String, CacheConfig)},
 * which is idempotent per-name: the first call constructs the cache through
 * the provider; subsequent calls with the same name return the same instance
 * (the {@code CacheConfig} on later calls is ignored). This matches how most
 * cache-using code wants to interact with the registry — declare the cache
 * once at the call site, treat the {@code Caches.named(...)} expression as
 * effectively a singleton lookup.
 *
 * <p>Test override: {@link #installProvider(CacheProvider)} bypasses
 * {@link ServiceLoader} so a test-only provider (e.g. one backed by
 * {@link ConcurrentHashMap} for deterministic asserts) can be wired without
 * mocking. Production code should never call this.
 */
public final class Caches {

    private static volatile CacheProvider provider;
    private static final ConcurrentMap<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();

    private Caches() {}

    /**
     * Resolve the single {@link CacheProvider} via {@link ServiceLoader}.
     * Called once from {@code Play.start()} before the plugin lifecycle.
     * Idempotent: if a provider was already installed (typically by tests
     * via {@link #installProvider}), this is a no-op.
     */
    public static synchronized void init() {
        if (provider != null) {
            // Already installed — typically by a test that wired its own provider
            // before Play.start() ran. Honor the test override.
            return;
        }
        List<CacheProvider> found = new ArrayList<>();
        for (CacheProvider p : ServiceLoader.load(CacheProvider.class, Play.classloader)) {
            found.add(p);
        }
        // Fallback: try the system classloader if Play.classloader saw nothing.
        // Happens in tests that boot before ApplicationClassloader is installed.
        if (found.isEmpty()) {
            for (CacheProvider p : ServiceLoader.load(CacheProvider.class)) {
                found.add(p);
            }
        }
        String selected = Play.configuration == null ? "" : Play.configuration.getProperty("cache.provider", "").trim();
        provider = selectProvider(found, selected);
        if (selected.isEmpty()) {
            Logger.info("Cache provider: %s", provider.name());
        } else {
            Logger.info("Cache provider: %s (selected via cache.provider)", provider.name());
        }
    }

    /**
     * Apply the resolution rules to a fixed candidate list. Package-private
     * so {@code CachesProviderResolutionTest} can drive it without round-
     * tripping through {@link ServiceLoader}.
     */
    static CacheProvider selectProvider(List<CacheProvider> found, String selected) {
        if (found.isEmpty()) {
            throw new ConfigurationException(
                "No play.cache.CacheProvider found on the classpath. The framework ships "
                + "play.cache.caffeine.CaffeineCacheProvider by default — if you've "
                + "deliberately stripped it, register an alternate provider via "
                + "META-INF/services/play.cache.CacheProvider.");
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        if (selected == null || selected.isEmpty()) {
            throw new ConfigurationException(
                "Multiple play.cache.CacheProvider implementations on the classpath ("
                + namesOf(found) + "). Set cache.provider=<name> in application.conf to choose one.");
        }
        for (CacheProvider p : found) {
            if (selected.equals(p.name())) {
                return p;
            }
        }
        throw new ConfigurationException(
            "cache.provider=" + selected + " does not match any registered CacheProvider. "
            + "Available providers report names: " + namesOf(found));
    }

    /**
     * Stop the active provider and forget all named caches. Called from
     * {@code Play.stop()}. Safe to call before {@link #init()} or twice in
     * a row.
     */
    public static synchronized void stop() {
        if (provider != null) {
            try {
                provider.stop();
            } catch (Throwable t) {
                Logger.warn(t, "Cache provider %s threw on stop", provider.name());
            }
        }
        caches.clear();
        provider = null;
    }

    /**
     * The active {@link CacheProvider}, or {@code null} if {@link #init()}
     * has not yet run (or {@link #stop()} has been called since). Used by
     * {@code MetricsPlugin} to dispatch provider-specific metrics binding.
     */
    public static CacheProvider provider() {
        return provider;
    }

    /**
     * Look up or create the cache registered under {@code name}. The first
     * call for a given name constructs the cache via the active provider
     * using {@code config}; subsequent calls return the same instance
     * regardless of the {@code config} passed.
     *
     * <p>Caller is responsible for the {@code <K, V>} types — there is no
     * runtime check that subsequent {@code named(name, ...)} calls request
     * the same parameterization. Mismatched types will surface as a
     * {@link ClassCastException} at first use.
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Cache<K, V> named(String name, CacheConfig config) {
        if (provider == null) {
            throw new IllegalStateException(
                "Caches.init() has not run. Caches.named(\"" + name + "\", ...) called before "
                + "the cache subsystem was initialized — typically a test that bypassed Play.start().");
        }
        return (Cache<K, V>) caches.computeIfAbsent(name, n -> provider.create(n, config));
    }

    /**
     * Invalidate every named cache. Used by {@code ApplicationClassloader}
     * on dev-mode hot reload — when application classes are reloaded,
     * cached {@code Result} objects and template fragments may reference
     * stale class instances and must be discarded.
     */
    public static void invalidateAll() {
        for (Cache<?, ?> c : caches.values()) {
            c.invalidateAll();
        }
    }

    /**
     * Re-bind Micrometer metrics on the active provider against
     * {@code registry}. Called by {@code MetricsPlugin.onApplicationStart}
     * after the Prometheus registry is installed. No-op when no provider is
     * active.
     */
    public static void bindMetricsToRegistry(MeterRegistry registry) {
        if (provider == null) return;
        try {
            provider.bindMetricsToRegistry(registry);
        } catch (Throwable t) {
            Logger.warn(t, "Cache provider %s failed to bind metrics", provider.name());
        }
    }

    /**
     * <strong>Test-only.</strong> Bypass {@link ServiceLoader} resolution
     * and install {@code testProvider} as the active provider. Stops the
     * previous provider (if any) and clears the named-cache registry so
     * the next {@link #named} call goes through {@code testProvider}.
     *
     * <p>Production code should never call this — it exists so unit tests
     * can wire a {@link ConcurrentHashMap}-backed provider for
     * deterministic asserts without a real cache backend on the classpath.
     */
    public static synchronized void installProvider(CacheProvider testProvider) {
        if (provider != null && provider != testProvider) {
            try {
                provider.stop();
            } catch (Throwable t) {
                Logger.warn(t, "Cache provider %s threw on stop during installProvider", provider.name());
            }
        }
        provider = testProvider;
        caches.clear();
    }

    private static String namesOf(List<CacheProvider> providers) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<CacheProvider> it = providers.iterator(); it.hasNext(); ) {
            sb.append(it.next().name());
            if (it.hasNext()) sb.append(", ");
        }
        return sb.toString();
    }
}
