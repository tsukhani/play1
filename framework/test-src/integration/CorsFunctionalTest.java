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
 * PF-6 end-to-end: stands up the real Netty server and asserts the {@code CorsPlugin}
 * short-circuits OPTIONS preflight requests with a 204 + the configured CORS headers,
 * and that simple GET requests carrying an {@code Origin} header receive the
 * reflected {@code Access-Control-Allow-Origin} header.
 *
 * <p>The testapp config (see {@code testapp/conf/application.conf}) sets
 * {@code cors.enabled=true} with a single allowed origin {@code https://example.com}.</p>
 */
public class CorsFunctionalTest {

    private static final String BASE = "https://localhost:19443";
    private static final String ORIGIN = "https://example.com";

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void preflightReturns204WithCorsHeaders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/json"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .build();

        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode(),
                () -> "expected 204 from preflight, body was: " + response.body());
        assertHeader(response, "Access-Control-Allow-Origin", ORIGIN);
        assertHeader(response, "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        assertHeader(response, "Access-Control-Allow-Headers", "Content-Type,Authorization");
        assertHeader(response, "Access-Control-Max-Age", "600");
        String vary = response.headers().firstValue("vary").orElse("");
        assertTrue(vary.toLowerCase().contains("origin"),
                "Vary header must contain Origin for non-wildcard CORS; got: " + vary);
    }

    @Test
    void simpleGetWithOriginEchoesAllowOrigin() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/json"))
                .header("Origin", ORIGIN)
                .GET()
                .build();

        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertHeader(response, "Access-Control-Allow-Origin", ORIGIN);
        assertHeader(response, "Access-Control-Expose-Headers", "X-Trace-Id");
    }

    @Test
    void disallowedOriginIsNotEchoed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/json"))
                .header("Origin", "https://evil.com")
                .GET()
                .build();

        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty(),
                "disallowed origin must not produce an Access-Control-Allow-Origin header");
    }

    private static void assertHeader(HttpResponse<?> response, String name, String expectedValue) {
        String actual = response.headers().firstValue(name.toLowerCase()).orElse(null);
        assertEquals(expectedValue, actual,
                () -> name + " header missing or wrong on " + response.statusCode() + " response");
    }

    private static HttpClient client() throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, new TrustManager[]{new TrustAllX509Manager()}, null);
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(sslCtx)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static final class TrustAllX509Manager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
