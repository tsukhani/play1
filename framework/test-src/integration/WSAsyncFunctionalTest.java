package integration;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import play.Play;
import play.libs.WS;
import play.libs.WS.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-107 functional test: exercises {@link play.libs.WS} (OkHttp 5 transport)
 * end-to-end against the same Netty server the rest of the integration suite
 * uses. The PF-104 migration preserved the WS public surface and passed the
 * full ant test suite, but no automated test pinned the integration acceptance
 * criterion: outbound HTTPS, HTTP/2, and cleartext HTTP against a real server.
 * This class fills that gap.
 *
 * <p>HTTPS variants need the OkHttp client to trust the self-signed test cert.
 * WSAsync wires its SSL setup from {@code Play.configuration}'s
 * {@code ssl.keyStore} / {@code ssl.keyStorePassword} / {@code ssl.cavalidation}
 * at first WS use; we point those at a JKS built from the same PEM the Netty
 * SSL pipeline serves, and set {@code ssl.cavalidation=false} so the trust
 * manager accepts the self-signed chain. WSAsync caches its {@code SSLContext}
 * in a static field, so any earlier WS use in this JVM would freeze in the
 * wrong setup — no other integration test touches WS, so this {@code @BeforeAll}
 * is the first caller in the suite JVM.
 */
public class WSAsyncFunctionalTest {

    private static final String HTTPS_BASE = "https://localhost:19443";
    private static final String HTTP_BASE = "http://127.0.0.1:19080";

    @BeforeAll
    static void startServer() throws Exception {
        IntegrationServer.ensureStarted();

        // Generate a JKS containing the test cert and point WS at it. Has to
        // happen after Play.init (configuration is loaded from application.conf
        // there) but before any WS.url() call (WSAsync's static sslCTX caches
        // on first construction).
        Path keystore = buildTrustStoreFromFixture();
        Play.configuration.setProperty("ssl.keyStore", keystore.toAbsolutePath().toString());
        Play.configuration.setProperty("ssl.keyStorePassword", "changeit");
        Play.configuration.setProperty("ssl.cavalidation", "false");
    }

    @Test
    void httpsGetReturnsBody() {
        HttpResponse r = WS.url(HTTPS_BASE + "/json").get();
        assertEquals(200, r.getStatus().intValue());
        assertTrue(r.getString().contains("\"status\":\"ok\""),
                "expected JSON body, got: " + r.getString());
    }

    @Test
    void httpsGetExercisesH2AlpnPath() {
        // OkHttp's default protocol list is [HTTP_2, HTTP_1_1]; against the
        // Netty server (which advertises h2 first via ALPN) the wire is HTTP/2.
        // The WS public API does not surface the negotiated protocol, so the
        // assertion is "request succeeded" — the same h2-or-bust ALPN config
        // that Http2FunctionalTest validates explicitly is what serves this
        // request. A regression on the h2 codec would surface here as a
        // non-200 or a malformed body.
        HttpResponse r = WS.url(HTTPS_BASE + "/json").get();
        assertEquals(200, r.getStatus().intValue());
        assertEquals("application/json; charset=utf-8",
                r.getHeader("Content-Type").toLowerCase());
    }

    @Test
    void cleartextGetCompletesUnderOneSecond() {
        // The original motivation for PF-104: JDK HttpClient sent `Upgrade: h2c`
        // on cleartext GETs and hung indefinitely against servers that ack the
        // header without delivering the h2 upgrade preface. OkHttp does not
        // emit that upgrade, so the call is a plain HTTP/1.1 round-trip. A
        // sub-second budget is generous enough to never flake on a healthy
        // host and tight enough that a regression to the hang behavior would
        // blow the integration-test timeout.
        HttpResponse r = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> WS.url(HTTP_BASE + "/json").get());
        assertEquals(200, r.getStatus().intValue());
        assertTrue(r.getString().contains("\"framework\":\"play\""));
    }

    @Test
    void asyncGetCompletesViaPromise() throws Exception {
        // Confirms the Promise pipeline still resolves through OkHttp's
        // enqueue/Callback bridge. 5s is well above any reasonable local round
        // trip but short enough to fail the test before the JUnit timeout.
        HttpResponse r = WS.url(HTTPS_BASE + "/json").getAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(r);
        assertEquals(200, r.getStatus().intValue());
    }

    @Test
    void postFormDataRoundTrips() {
        // /echo reads params.get("message") and renders "echo:<message>".
        // Exercises the application/x-www-form-urlencoded body path in WSAsync.
        HttpResponse r = WS.url(HTTPS_BASE + "/echo")
                .setParameter("message", "hello-ws")
                .post();
        assertEquals(200, r.getStatus().intValue());
        assertEquals("echo:hello-ws", r.getString());
    }

    @Test
    void postJsonBodyRoundTrips() {
        // Exercises the raw-body path: WSAsync emits the JSON as the request
        // body with the caller-supplied Content-Type. The /post-json action
        // echoes both the received body and the parsed Content-Type back.
        String payload = "{\"hello\":\"world\",\"n\":42}";
        HttpResponse r = WS.url(HTTPS_BASE + "/post-json")
                .mimeType("application/json")
                .body(payload)
                .post();
        assertEquals(200, r.getStatus().intValue());
        String body = r.getString();
        assertTrue(body.contains("\\\"hello\\\":\\\"world\\\""),
                "expected the request body to round-trip; got: " + body);
        assertTrue(body.contains("application/json"),
                "expected Content-Type to round-trip; got: " + body);
    }

    @Test
    void noFollowRedirectsClientSharesPool() {
        // The OkHttp follow-redirects and no-redirects clients share a single
        // dispatcher and connection pool (see WSAsync.buildClient). Exercising
        // the no-follow path proves the second client is wired and that
        // followRedirects=false is honoured.
        HttpResponse r = WS.url(HTTPS_BASE + "/redirect").followRedirects(false).get();
        assertEquals(302, r.getStatus().intValue(),
                "followRedirects(false) should surface the redirect status");
        assertNotNull(r.getHeader("Location"), "302 response must carry a Location header");
    }

    // ------------------------------------------------------------------------
    // JKS truststore for the test cert. WSSSLContext requires a JKS keystore
    // file (not raw PEM), so we transcode the fixture PEM into a JKS once per
    // JVM and hand WSAsync the path.
    // ------------------------------------------------------------------------

    private static Path buildTrustStoreFromFixture() throws Exception {
        File testApp = new File(System.getProperty("user.dir"), "test-src/integration/testapp");
        File pem = new File(testApp, "certs/host.cert");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (var in = Files.newInputStream(pem.toPath())) {
            cert = cf.generateCertificate(in);
        }
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, "changeit".toCharArray());
        ks.setCertificateEntry("integration-test-host", cert);
        Path out = Files.createTempFile("pf107-truststore", ".jks");
        try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
            ks.store(fos, "changeit".toCharArray());
        }
        out.toFile().deleteOnExit();
        return out;
    }
}
