package play.cache;

import org.junit.jupiter.api.Test;
import play.exceptions.ConfigurationException;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PF-88 provider-resolution rules. Drives {@link Caches#selectProvider} with
 * fabricated candidate lists rather than touching the real
 * {@link java.util.ServiceLoader} — that way the SPI registration on the
 * runtime classpath ({@code play.cache.caffeine.CaffeineCacheProvider}) doesn't
 * leak into the assertions, and we can exercise multi-provider behavior
 * without producing a second {@code META-INF/services} file just for tests.
 */
public class CachesProviderResolutionTest {

    @Test
    public void zeroProvidersFailsFast() {
        assertThatThrownBy(() -> Caches.selectProvider(List.of(), ""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("No play.cache.CacheProvider found");
    }

    @Test
    public void singleProviderIsSelectedWithoutConfig() {
        CacheProvider only = stub("only");
        assertThat(Caches.selectProvider(List.of(only), "")).isSameAs(only);
        // Even an explicit cache.provider value is irrelevant when there's
        // only one candidate — the selector falls through the size-1 fast path.
        assertThat(Caches.selectProvider(List.of(only), "ignored-name")).isSameAs(only);
    }

    @Test
    public void multipleProvidersWithoutConfigFailsWithDiagnostic() {
        assertThatThrownBy(() -> Caches.selectProvider(List.of(stub("a"), stub("b")), ""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Multiple play.cache.CacheProvider")
                .hasMessageContaining("a, b")
                .hasMessageContaining("cache.provider");
    }

    @Test
    public void multipleProvidersWithMatchingConfigPicksByName() {
        CacheProvider a = stub("alpha");
        CacheProvider b = stub("bravo");
        assertThat(Caches.selectProvider(List.of(a, b), "bravo")).isSameAs(b);
        assertThat(Caches.selectProvider(List.of(a, b), "alpha")).isSameAs(a);
    }

    @Test
    public void multipleProvidersWithMismatchedConfigFailsWithDiagnostic() {
        assertThatThrownBy(() -> Caches.selectProvider(List.of(stub("a"), stub("b")), "missing"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cache.provider=missing")
                .hasMessageContaining("a, b");
    }

    private static CacheProvider stub(String name) {
        return new CacheProvider() {
            @Override public String name() { return name; }
            @Override public <K, V> Cache<K, V> create(String cacheName, CacheConfig config) {
                throw new UnsupportedOperationException("stub provider " + name + " never creates");
            }
            @Override public void stop() {}
        };
    }
}
