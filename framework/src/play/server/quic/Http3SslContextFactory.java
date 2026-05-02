package play.server.quic;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;

import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import play.Play;

/**
 * PF-57: lazily-built, statically-cached {@link QuicSslContext} for the HTTP/3 path.
 * Mirrors {@link play.server.ssl.SslHttpServerPipelineFactory#buildSslContext} —
 * supports both PEM cert+key files ({@code certificate.file} / {@code certificate.key.file})
 * and JKS keystores ({@code keystore.file} / {@code keystore.password}). Uses
 * {@link Http3#supportedApplicationProtocols()} so the ALPN advertisement always tracks
 * whatever h3 draft Netty currently negotiates.
 *
 * <p>Cache lifetime is the JVM. Cert rotation requires a server restart — same constraint
 * that applies to the cached SslContext on the h2/h1 path.
 */
public final class Http3SslContextFactory {

    private static volatile QuicSslContext context;

    private Http3SslContextFactory() {}

    /** Build (if necessary) and return the QUIC SSL context. */
    public static QuicSslContext getServerContext() throws Exception {
        QuicSslContext ctx = context;
        if (ctx == null) {
            synchronized (Http3SslContextFactory.class) {
                ctx = context;
                if (ctx == null) {
                    context = ctx = build();
                }
            }
        }
        return ctx;
    }

    private static QuicSslContext build() throws Exception {
        Properties p = Play.configuration;
        File certFile = Play.getFile(p.getProperty("certificate.file", "conf/host.cert"));
        File keyFile = Play.getFile(p.getProperty("certificate.key.file", "conf/host.key"));

        // PEM path: both cert + key files present. Same precedence as the h2 path so users
        // configuring PEM see consistent behaviour on TCP and UDP.
        if (certFile.exists() && keyFile.exists()) {
            return QuicSslContextBuilder.forServer(keyFile, /*keyPassword*/ null, certFile)
                    .applicationProtocols(Http3.supportedApplicationProtocols())
                    .build();
        }

        // JKS path: load the configured keystore and wrap a KeyManagerFactory. Same defaults
        // as the h2/h1 path so an existing keystore deployment activates HTTP/3 implicitly
        // when https.port is set (no separate cert config needed).
        File keystoreFile = Play.getFile(p.getProperty("keystore.file", "conf/certificate.jks"));
        if (!keystoreFile.exists()) {
            throw new IllegalStateException(
                    "PF-57: HTTP/3 needs an HTTPS cert source. Configure either "
                            + "PEM (certificate.file + certificate.key.file) or JKS (keystore.file). "
                            + "Looked for cert at " + certFile.getAbsolutePath()
                            + ", key at " + keyFile.getAbsolutePath()
                            + ", keystore at " + keystoreFile.getAbsolutePath() + ".");
        }
        String passwordStr = p.getProperty("keystore.password", "secret");
        char[] password = passwordStr.toCharArray();
        KeyStore ks = KeyStore.getInstance(p.getProperty("keystore.algorithm", "JKS"));
        try (FileInputStream in = new FileInputStream(keystoreFile)) {
            ks.load(in, password);
        }
        String kmfAlg = java.security.Security.getProperty("ssl.KeyManagerFactory.algorithm");
        if (kmfAlg == null) kmfAlg = "SunX509";
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(kmfAlg);
        kmf.init(ks, password);
        return QuicSslContextBuilder.forServer(kmf, passwordStr)
                .applicationProtocols(Http3.supportedApplicationProtocols())
                .build();
    }
}
