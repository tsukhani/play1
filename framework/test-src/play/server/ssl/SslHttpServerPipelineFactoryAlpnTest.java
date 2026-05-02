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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-58 / PF-66: verifies that the SSL pipeline factory advertises ALPN protocols in the
 * right priority order ({@code h2}, {@code http/1.1}) — ALPN is now always on when HTTPS
 * is configured (the {@code play.http2.enabled} opt-in flag was removed; ALPN gracefully
 * degrades to http/1.1 for non-h2 clients, so there's no scenario worth gating). The
 * unified Netty SslContext path (PF-66) builds correctly for both PEM and JKS cert sources.
 *
 * <p>The actual handshake-level ALPN selection is exercised end-to-end by
 * {@code integration.Http2FunctionalTest}, which stands up a real Netty server with a
 * self-signed test cert and asserts both versions return identical bodies.
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
        tmpDir = Files.createTempDirectory("pf66-test-");
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
            // Best-effort cleanup of any keystores written during the test
            File[] files = tmpDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) f.delete();
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
    void buildSslContextFromJksAdvertisesH2ThenHttp1() throws Exception {
        // PF-66 + flag-removal AC: a JKS keystore unconditionally produces an SslContext
        // whose ALPN negotiator advertises h2 then http/1.1. Pre-PF-66 the JKS path went
        // through the JDK SSLEngine without ALPN; pre-flag-removal the ALPN was gated on
        // play.http2.enabled. Now ALPN is always configured.
        File keystore = generateJksKeystore("conf/certificate.jks", "test-pass");
        Play.configuration.setProperty("keystore.file", relativizeToApp(keystore));
        Play.configuration.setProperty("keystore.password", "test-pass");

        SslContext ctx = SslHttpServerPipelineFactory.buildSslContext();

        assertNotNull(ctx, "JKS SslContext must build");
        assertTrue(ctx.isServer(), "Built SslContext must be a server context");
        ApplicationProtocolNegotiator negotiator = ctx.applicationProtocolNegotiator();
        assertNotNull(negotiator, "ALPN negotiator must always be configured");
        List<String> advertised = negotiator.protocols();
        assertEquals(List.of("h2", "http/1.1"), advertised,
                "ALPN must advertise h2 then http/1.1 for JKS keystore");
    }

    /**
     * Generate a self-signed JKS keystore at the given path under {@link #tmpDir} by
     * shelling out to {@code keytool}. Mirrors what {@code play enable-https} (PF-67)
     * does in production, so we exercise a keystore the framework would actually
     * generate. Avoids pulling in additional BouncyCastle transitive deps just for
     * test cert construction.
     */
    private File generateJksKeystore(String relativePath, String password) throws Exception {
        File out = new File(tmpDir.toFile(), relativePath);
        out.getParentFile().mkdirs();
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "play",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-keystore", out.getAbsolutePath(),
                "-storepass", password,
                "-keypass", password,
                "-storetype", "JKS",
                "-dname", "CN=localhost",
                "-validity", "365"
        ).redirectErrorStream(true);
        Process p = pb.start();
        int rc = p.waitFor();
        if (rc != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("keytool failed (rc=" + rc + "): " + output);
        }
        return out;
    }

    private String relativizeToApp(File f) {
        return Play.applicationPath.toPath().relativize(f.toPath()).toString();
    }
}
