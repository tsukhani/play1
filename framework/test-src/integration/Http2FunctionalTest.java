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
 * PF-58 functional test: stands up the Netty server with HTTPS + HTTP/2 enabled
 * (configured via the {@code %test.} block in {@code testapp/conf/application.conf})
 * and proves that an ALPN-capable client negotiates h2 while a client pinned to
 * HTTP/1.1 still gets the same response body from the same controller action.
 *
 * <p>Distinct from {@link RequestLifecycleTest}, which exercises the in-process
 * {@link play.test.FunctionalTest} dispatch path. Only a real Netty bind exercises the
 * SSL pipeline factory's ALPN branch — the in-process path skips Netty entirely.
 *
 * <p>Test cert at {@code testapp/conf/test-host.{cert,key}} is self-signed RSA-2048
 * with {@code SAN=DNS:localhost,IP:127.0.0.1}, valid 10 years from generation. The
 * client uses a permissive {@code X509TrustManager} so the host's CA store is
 * irrelevant; the SAN matches "localhost" so the JDK's built-in HTTPS endpoint-id
 * verification passes without disabling.
 */
public class Http2FunctionalTest {

    private static final String BASE = "https://localhost:19443";

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void http1ClientGetsH1Response() throws Exception {
        HttpResponse<String> response = client(HttpClient.Version.HTTP_1_1)
                .send(HttpRequest.newBuilder(URI.create(BASE + "/json")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(HttpClient.Version.HTTP_1_1, response.version(),
                "client requested HTTP/1.1 — server must not silently upgrade to h2");
        assertTrue(response.body().contains("\"status\":\"ok\""),
                "expected JSON body, got: " + response.body());
    }

    @Test
    void http2ClientNegotiatesH2WithSameBody() throws Exception {
        HttpResponse<String> h2 = client(HttpClient.Version.HTTP_2)
                .send(HttpRequest.newBuilder(URI.create(BASE + "/json")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, h2.statusCode());
        assertEquals(HttpClient.Version.HTTP_2, h2.version(),
                "ALPN negotiation should produce h2; server advertises h2 first");

        HttpResponse<String> h1 = client(HttpClient.Version.HTTP_1_1)
                .send(HttpRequest.newBuilder(URI.create(BASE + "/json")).build(),
                        HttpResponse.BodyHandlers.ofString());

        // PF-58 AC #3: identical response body across versions — the same controller
        // serves both, so any divergence here means the h2 frame-to-HttpObject codec
        // is corrupting the response shape on its way through Http2StreamPlayHandler.
        assertEquals(h1.body(), h2.body(),
                "controller body must be identical regardless of negotiated HTTP version");
    }

    @Test
    void tlsResponsesAdvertiseH3ViaAltSvc() throws Exception {
        // PF-57 cascade: when h3 is bound (which is automatic when https.port is set
        // and native QUIC is available), every TLS-protected response carries an
        // Alt-Svc header pointing browsers at the QUIC endpoint on the SAME port as
        // HTTPS (TCP and UDP have separate port spaces). Asserting this on the h2
        // path (since h2 is what cold browsers land on first) verifies the
        // negotiation chain wires up correctly:
        // TCP → TLS → ALPN h2 → Alt-Svc → next request flips to h3.
        HttpResponse<String> h2 = client(HttpClient.Version.HTTP_2)
                .send(HttpRequest.newBuilder(URI.create(BASE + "/json")).build(),
                        HttpResponse.BodyHandlers.ofString());
        String altSvc = h2.headers().firstValue("alt-svc").orElse(null);
        assertNotNull(altSvc, "Alt-Svc header must be present on TLS responses when h3 is bound");
        assertTrue(altSvc.contains("h3=\":19443\""),
                "Alt-Svc must advertise h3 endpoint at the configured port; got: " + altSvc);
        assertTrue(altSvc.contains("ma="),
                "Alt-Svc must include max-age (ma=) for client-side caching; got: " + altSvc);
    }

    private static HttpClient client(HttpClient.Version version) throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, new TrustManager[]{new TrustAllX509Manager()}, null);
        return HttpClient.newBuilder()
                .version(version)
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
