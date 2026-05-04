package integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-85 / PF-86 functional test: drives the real Netty server through the
 * MetricsPlugin {@code rawInvocation} short-circuit at {@code /@metrics} and
 * asserts the Prometheus exposition body contains the meters bound by the full
 * plugin lifecycle — Caffeine cache (PF-86), HikariCP pool (PF-85), and the
 * baseline JVM binders.
 *
 * <p>FunctionalTest's in-process {@code GET()} path bypasses
 * {@code rawInvocation} (it calls {@code ActionInvoker.invoke} directly), so
 * verifying the scrape endpoint requires a real server bind. This test uses the
 * same {@link IntegrationServer} fixture as the SSE/h2/h3 tests so all four
 * share one Netty bootstrap.
 *
 * <p>Tag assertions catch two regressions that would otherwise be silent:
 * {@code cache="play_cache"} verifies the {@code MetricsPlugin ->
 * CaffeineImpl.bindMetrics} wiring fires, and {@code pool="default"} verifies
 * the M2 {@code setPoolName(dbConfig.configName)} path — without it Hikari
 * auto-generates {@code HikariPool-1} and the test would fail loudly.
 */
public class MetricsFunctionalTest {

    private static final String BASE = "https://localhost:19443";

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void metricsEndpointExposesCacheHikariAndJvmSeries() throws Exception {
        // Touch the cache so cache.gets has at least one hit + miss recorded.
        // cache.size would still publish at value 0 without this, but exercising
        // the cache makes the assertion meaningful when grepping the output.
        play.cache.Cache.set("metrics-functional-test-key", "v", "60s");
        play.cache.Cache.get("metrics-functional-test-key");
        play.cache.Cache.get("metrics-functional-test-miss");

        HttpResponse<String> r = httpsClient()
                .send(HttpRequest.newBuilder(URI.create(BASE + "/@metrics")).build(),
                      HttpResponse.BodyHandlers.ofString());

        assertEquals(200, r.statusCode(), "expected 200 from /@metrics");
        assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("text/plain"),
                "expected text/plain content type, got: " + r.headers().firstValue("content-type"));

        String body = r.body();

        // PF-86: CaffeineCacheMetrics binds gauges + counters under cache.* with
        // a "cache" tag scoping each meter to the binder name (play_cache).
        assertTrue(body.contains("cache_size{cache=\"play_cache\""),
                "expected cache_size{cache=\"play_cache\",...} line — body was:\n" + body);
        assertTrue(body.contains("cache_gets_total{cache=\"play_cache\""),
                "expected cache_gets_total{cache=\"play_cache\",...} line — body was:\n" + body);

        // PF-85 + M2: HikariCP's MicrometerMetricsTrackerFactory emits hikaricp_*
        // meters tagged with pool=<dbName>. The testapp uses the default DB, so
        // the pool tag must be "default" (not "HikariPool-1").
        assertTrue(body.contains("hikaricp_connections{pool=\"default\""),
                "expected hikaricp_connections{pool=\"default\",...} line — "
                        + "if the pool tag is HikariPool-1 the M2 setPoolName fix regressed. body was:\n" + body);

        // Sanity check that the JVM binders also fired — proves the registry
        // is the live Prometheus one, not the SimpleMeterRegistry default.
        assertTrue(body.contains("jvm_memory_used_bytes"),
                "expected jvm_memory_used_bytes — the registry isn't the live Prometheus one. body was:\n" + body);
    }

    private static HttpClient httpsClient() throws Exception {
        TrustManager[] permissive = new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, permissive, null);
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(ssl)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
