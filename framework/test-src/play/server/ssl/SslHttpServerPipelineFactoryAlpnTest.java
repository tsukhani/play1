package play.server.ssl;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;

import play.Play;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-58 / PF-66 / PF-68: verifies that the SSL pipeline factory advertises ALPN protocols
 * in the right priority order ({@code h2}, {@code http/1.1}) — ALPN is always on when HTTPS
 * is configured. PF-68 removed JKS support; the cert source is now PEM-only
 * ({@code certificate.file} + {@code certificate.key.file}).
 *
 * <p>The actual handshake-level ALPN selection is exercised end-to-end by
 * {@code integration.Http2FunctionalTest}, which stands up a real Netty server with a
 * self-signed PEM cert and asserts both versions return identical bodies.
 */
public class SslHttpServerPipelineFactoryAlpnTest {

    private Properties savedConfig;
    private File savedApplicationPath;
    private Path tmpDir;

    @BeforeEach
    void setUp() throws Exception {
        savedConfig = Play.configuration;
        savedApplicationPath = Play.applicationPath;
        Play.configuration = new Properties();
        tmpDir = Files.createTempDirectory("pf68-test-");
        Play.applicationPath = tmpDir.toFile();
        // PF-66: SslContext is statically cached. Tests in this class build their own
        // contexts, so reset the cache before each test to avoid leaking state across runs.
        resetCachedSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        Play.configuration = savedConfig;
        Play.applicationPath = savedApplicationPath;
        resetCachedSslContext();
        if (tmpDir != null) {
            File[] files = tmpDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        File[] inner = f.listFiles();
                        if (inner != null) for (File i : inner) i.delete();
                        f.delete();
                    } else {
                        f.delete();
                    }
                }
            }
            tmpDir.toFile().delete();
        }
    }

    private static void resetCachedSslContext() throws Exception {
        java.lang.reflect.Field f = SslHttpServerPipelineFactory.class.getDeclaredField("cachedSslContext");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Test
    void alpnProtocolListAdvertisesH2ThenHttp1() {
        // PF-58 AC #3: priority order matters — h2 must come before http/1.1 so an
        // ALPN-capable client gets h2, while h1.1-only clients still negotiate cleanly.
        // Regressing this order silently downgrades every h2 client to h1.1.
        assertArrayEquals(new String[]{"h2", "http/1.1"}, SslHttpServerPipelineFactory.ALPN_PROTOCOLS,
                "ALPN_PROTOCOLS must be h2 then http/1.1 in priority order");
    }

    @Test
    void buildSslContextFromPemAdvertisesH2ThenHttp1() throws Exception {
        // PF-68: a PEM cert+key pair produces an SslContext whose ALPN negotiator
        // advertises h2 then http/1.1. PEM is the only cert source post-PF-68.
        // PF-69: the canonical PEM location is certs/, not conf/.
        generatePemCertAndKey("certs/host.cert", "certs/host.key");
        Play.configuration.setProperty("certificate.file", "certs/host.cert");
        Play.configuration.setProperty("certificate.key.file", "certs/host.key");

        SslContext ctx = SslHttpServerPipelineFactory.buildSslContext();

        assertNotNull(ctx, "PEM SslContext must build");
        assertTrue(ctx.isServer(), "Built SslContext must be a server context");
        ApplicationProtocolNegotiator negotiator = ctx.applicationProtocolNegotiator();
        assertNotNull(negotiator, "ALPN negotiator must always be configured");
        List<String> advertised = negotiator.protocols();
        assertEquals(List.of("h2", "http/1.1"), advertised,
                "ALPN must advertise h2 then http/1.1 for PEM cert+key");
    }

    @Test
    void buildSslContextThrowsWhenCertFilesMissing() {
        // PF-68: no PEM files on disk + no other cert source = clean
        // IllegalStateException naming the expected paths. Pre-PF-68 a missing PEM
        // would silently fall through to the JKS branch and emit a confused error.
        // PF-69: paths reflect the new certs/ canonical location.
        Play.configuration.setProperty("certificate.file", "certs/host.cert");
        Play.configuration.setProperty("certificate.key.file", "certs/host.key");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                SslHttpServerPipelineFactory::buildSslContext);
        String msg = ex.getMessage();
        assertTrue(msg.contains("PEM-only"), "error must declare PEM-only contract: " + msg);
        assertTrue(msg.contains("host.cert"), "error must name expected cert path: " + msg);
        assertTrue(msg.contains("host.key"), "error must name expected key path: " + msg);
    }

    /**
     * Generate a self-signed PEM cert+key pair at the given paths under {@link #tmpDir}
     * via the {@code openssl} CLI — mirrors the openssl fallback in the production
     * {@code play enable-https} command (PF-68). Avoids any extra Java-level test deps.
     */
    private void generatePemCertAndKey(String certRelative, String keyRelative) throws Exception {
        File certOut = new File(tmpDir.toFile(), certRelative);
        File keyOut = new File(tmpDir.toFile(), keyRelative);
        certOut.getParentFile().mkdirs();
        keyOut.getParentFile().mkdirs();
        ProcessBuilder pb = new ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", keyOut.getAbsolutePath(),
                "-out", certOut.getAbsolutePath(),
                "-days", "365",
                "-subj", "/CN=localhost"
        ).redirectErrorStream(true);
        Process p = pb.start();
        int rc = p.waitFor();
        if (rc != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("openssl failed (rc=" + rc + "): " + output);
        }
    }
}
