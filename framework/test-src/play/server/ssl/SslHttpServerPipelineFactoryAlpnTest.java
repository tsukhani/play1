package play.server.ssl;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-58: verifies that the SSL pipeline factory advertises ALPN protocols in the right
 * priority order ({@code h2}, {@code http/1.1}) when {@code play.http2.enabled=true},
 * and that the flag itself parses correctly.
 *
 * <p>The actual handshake-level ALPN selection is exercised end-to-end by
 * {@code integration.Http2FunctionalTest}, which stands up a real Netty server with a
 * self-signed test cert and asserts both versions return identical bodies.
 */
public class SslHttpServerPipelineFactoryAlpnTest {

    private Properties savedConfig;

    @BeforeEach
    void setUp() {
        savedConfig = Play.configuration;
        Play.configuration = new Properties();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = savedConfig;
    }

    @Test
    void flagDefaultsFalseWhenAbsent() {
        assertFalse(SslHttpServerPipelineFactory.isHttp2Enabled());
    }

    @Test
    void flagFalseExplicit() {
        Play.configuration.setProperty("play.http2.enabled", "false");
        assertFalse(SslHttpServerPipelineFactory.isHttp2Enabled());
    }

    @Test
    void flagTrue() {
        Play.configuration.setProperty("play.http2.enabled", "true");
        assertTrue(SslHttpServerPipelineFactory.isHttp2Enabled());
    }

    @Test
    void alpnProtocolListAdvertisesH2ThenHttp1() {
        // PF-58 AC #3: priority order matters — h2 must come before http/1.1 so an
        // ALPN-capable client gets h2, while h1.1-only clients still negotiate cleanly.
        // Regressing this order silently downgrades every h2 client to h1.1.
        assertArrayEquals(new String[]{"h2", "http/1.1"}, SslHttpServerPipelineFactory.ALPN_PROTOCOLS,
                "ALPN_PROTOCOLS must be h2 then http/1.1 in priority order");
    }
}
