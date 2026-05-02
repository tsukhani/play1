package play.server.quic;

import java.io.File;
import java.util.Properties;

import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import play.Play;

/**
 * PF-57: lazily-built, statically-cached {@link QuicSslContext} for the HTTP/3 path.
 * Mirrors {@link play.server.ssl.SslHttpServerPipelineFactory#buildSslContext} —
 * PEM-only ({@code certificate.file} / {@code certificate.key.file}, with optional
 * {@code certificate.key.password} for encrypted keys). PF-68 dropped the JKS keystore
 * branch since every TLS configuration JKS expressed had a PEM equivalent and the
 * local-dev mkcert workflow is PEM-native. Uses {@link Http3#supportedApplicationProtocols()}
 * so the ALPN advertisement always tracks whatever h3 draft Netty currently negotiates.
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
        File certFile = Play.getFile(p.getProperty("certificate.file", "certs/host.cert"));
        File keyFile = Play.getFile(p.getProperty("certificate.key.file", "certs/host.key"));
        // null = unencrypted key (mkcert/openssl default).
        String keyPassword = p.getProperty("certificate.key.password");

        if (!certFile.exists() || !keyFile.exists()) {
            throw new IllegalStateException(
                    "PF-57/PF-68: HTTP/3 needs PEM cert+key files. Run play enable-https "
                            + "(generates them via mkcert or openssl), or set certificate.file + "
                            + "certificate.key.file manually. Looked for cert at "
                            + certFile.getAbsolutePath() + ", key at " + keyFile.getAbsolutePath() + ".");
        }

        return QuicSslContextBuilder.forServer(keyFile, keyPassword, certFile)
                .applicationProtocols(Http3.supportedApplicationProtocols())
                .build();
    }
}
