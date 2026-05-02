package integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-16 functional test: drives a real Netty server through the SSE pipeline
 * (response.chunked = true + writeChunk + Transfer-Encoding: chunked +
 * LastHttpContent terminator) and asserts the wire bytes match the SSE spec.
 *
 * <p>Uses the JDK's {@link HttpClient} over HTTP/1.1 — SSE works over h2 too,
 * but h1.1 lets us inspect the chunked transfer encoding output directly via
 * {@code BodyHandlers.ofString()} without needing a streaming parser. The
 * controller pushes three events and closes, so the response body is bounded
 * and easy to assert on.
 */
public class SseFunctionalTest {

    private static final String BASE = "https://localhost:19443";

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void streamHasCorrectHeaders() throws Exception {
        HttpResponse<String> r = httpsClient()
                .send(HttpRequest.newBuilder(URI.create(BASE + "/events")).build(),
                      HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        // Content-Type must be text/event-stream — set by openSSE().
        assertTrue(r.headers().firstValue("content-type").orElse("").contains("text/event-stream"),
                "expected Content-Type: text/event-stream, got: " + r.headers().firstValue("content-type"));
        // Cache-Control: no-cache — set by openSSE().
        assertTrue(r.headers().firstValue("cache-control").orElse("").contains("no-cache"),
                "expected Cache-Control: no-cache, got: " + r.headers().firstValue("cache-control"));
        // X-Accel-Buffering: no — proxy hint set by openSSE().
        assertEquals("no", r.headers().firstValue("x-accel-buffering").orElse(""),
                "expected X-Accel-Buffering: no");
    }

    @Test
    void streamFramingMatchesSseSpec() throws Exception {
        HttpResponse<String> r = httpsClient()
                .send(HttpRequest.newBuilder(URI.create(BASE + "/events")).build(),
                      HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());

        List<Frame> frames = parseFrames(r.body());
        assertEquals(4, frames.size(),
                "controller pushed 3 sends + 1 sendId — expected 4 frames, got: " + frames);

        // 1: anonymous send → only `data:` field
        Frame first = frames.get(0);
        assertNotNull(first.fields.get("data"));
        assertTrue(first.fields.get("data").contains("\"seq\":1"),
                "expected first frame to carry seq=1, got: " + first);

        // 2: sendEvent("milestone", ...) → both `event:` and `data:` fields
        Frame second = frames.get(1);
        assertEquals("milestone", second.fields.get("event"));
        assertTrue(second.fields.get("data").contains("\"seq\":2"));

        // 3: sendId → just `id:` field
        Frame third = frames.get(2);
        assertEquals("evt-3", third.fields.get("id"));

        // 4: anonymous send → `data:` field
        Frame fourth = frames.get(3);
        assertTrue(fourth.fields.get("data").contains("\"seq\":3"));
    }

    // ------------------------------------------------------------------------
    // SSE wire-format parser. Trivial per spec: frames separated by blank
    // lines, each line is "<field>: <value>". We only need single-line values
    // for these assertions.
    // ------------------------------------------------------------------------

    private static class Frame {
        final Map<String, String> fields = new LinkedHashMap<>();
        @Override public String toString() { return fields.toString(); }
    }

    private static List<Frame> parseFrames(String body) {
        List<Frame> frames = new ArrayList<>();
        Frame current = new Frame();
        for (String line : body.split("\n", -1)) {
            if (line.isEmpty()) {
                if (!current.fields.isEmpty()) {
                    frames.add(current);
                    current = new Frame();
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String field = line.substring(0, colon);
            String value = colon + 1 < line.length() && line.charAt(colon + 1) == ' '
                    ? line.substring(colon + 2) : line.substring(colon + 1);
            current.fields.put(field, value);
        }
        if (!current.fields.isEmpty()) frames.add(current);
        return frames;
    }

    // ------------------------------------------------------------------------
    // HTTPS client with permissive trust — same shape as Http2FunctionalTest.
    // ------------------------------------------------------------------------

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
