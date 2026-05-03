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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-5 end-to-end test: stands up the real Netty server and asserts that the default
 * security headers appear on every response path — controller success, 404 for unrouted
 * paths, 500 for thrown exceptions, and static asset serving. The whole point of
 * implementing the policy at the netty layer (rather than as a per-request plugin hook)
 * is that error and static paths don't bypass it; this test is what makes that claim true.
 *
 * <p>HTTPS-only so HSTS coverage is also asserted. Cert + client trust setup mirrors
 * {@link Http2FunctionalTest}.
 */
public class SecurityHeadersFunctionalTest {

    private static final String BASE = "https://localhost:19443";

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void controllerResponseHasSecurityHeaders() throws Exception {
        HttpResponse<String> response = client()
                .send(HttpRequest.newBuilder(URI.create(BASE + "/json")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertAllHeadersPresent(response);
    }

    @Test
    void notFoundResponseHasSecurityHeaders() throws Exception {
        HttpResponse<String> response = client()
                .send(HttpRequest.newBuilder(URI.create(BASE + "/no-such-route-here")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(), () -> "expected 404, body was: " + response.body());
        assertAllHeadersPresent(response);
    }

    @Test
    void serverErrorResponseHasSecurityHeaders() throws Exception {
        HttpResponse<String> response = client()
                .send(HttpRequest.newBuilder(URI.create(BASE + "/boom")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(500, response.statusCode());
        // The serve500 path builds its netty response from scratch; without the policy
        // hook these headers would be absent — that's the original PF-5 coverage gap.
        assertAllHeadersPresent(response);
    }

    @Test
    void staticAssetResponseHasSecurityHeaders() throws Exception {
        HttpResponse<String> response = client()
                .send(HttpRequest.newBuilder(URI.create(BASE + "/public/test.txt")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        // FileService.serve writes its own Content-Length / Content-Type but reuses the
        // same nettyResponse, so the policy must be applied before we hand off to it.
        assertAllHeadersPresent(response);
    }

    private static void assertAllHeadersPresent(HttpResponse<?> response) {
        assertHeader(response, "X-Content-Type-Options", "nosniff");
        assertHeader(response, "X-Frame-Options", "DENY");
        assertHeader(response, "Referrer-Policy", "strict-origin-when-cross-origin");
        assertHeader(response, "X-XSS-Protection", "0");
        assertHeader(response, "Content-Security-Policy", "default-src 'self'");
        // HSTS is HTTPS-only — this whole test runs over TLS, so it must be present.
        String hsts = response.headers().firstValue("strict-transport-security").orElse(null);
        assertNotNull(hsts, "HSTS header missing on TLS response");
        assertTrue(hsts.startsWith("max-age=31536000"),
                "HSTS must lead with the configured max-age; got: " + hsts);
        assertTrue(hsts.contains("includeSubDomains"),
                "HSTS must include includeSubDomains by default; got: " + hsts);
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
