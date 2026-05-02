package play.server.ssl;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;

import play.Logger;
import play.Play;
import play.server.HttpServerPipelineFactory;

public class SslHttpServerPipelineFactory extends HttpServerPipelineFactory {

    private final String pipelineConfig = Play.configuration.getProperty("play.ssl.netty.pipeline",
            "io.netty.handler.codec.http.HttpRequestDecoder,play.server.StreamChunkAggregator,io.netty.handler.codec.http.HttpResponseEncoder,io.netty.handler.stream.ChunkedWriteHandler,play.server.ssl.SslPlayHandler");

    // PF-65: see HttpServerPipelineFactory#sanitizeAggregatorMax — promote -1 to Integer.MAX_VALUE
    // so the documented "unlimited" sentinel doesn't crash an explicitly-wired HttpObjectAggregator.
    private static final int DEFAULT_AGGREGATOR_MAX = sanitizeAggregatorMax();

    private static int sanitizeAggregatorMax() {
        int v;
        try {
            v = Integer.parseInt(Play.configuration.getProperty("play.netty.maxContentLength", "1048576"));
        } catch (NumberFormatException nfe) {
            v = 1048576;
        }
        return v < 0 ? Integer.MAX_VALUE : v;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        // PF-66 + flag-removal: single unified Netty SslContext path for both PEM and JKS,
        // with ALPN always advertising h2 + http/1.1. Replaces the legacy JDK-SSLEngine
        // branch that previously handled the non-h2 case. Benefits: one cert-loading
        // code path, deterministic server-preferred ALPN selection (JDK SSLEngine's
        // ALPN on JDK 25 was implementation-defined and empirically preferred the
        // client's ordering, silently downgrading h2 to http/1.1).
        //
        // ALPN gracefully degrades: clients that don't offer h2 negotiate http/1.1
        // (NO_ADVERTISE failure behavior + h2/http/1.1 protocol list), so there's no
        // "ALPN off" mode worth preserving — it would be strictly worse for h2 clients
        // without making anything compatible for h1.1 clients.
        pipeline.addLast("ssl", buildSslHandler(ch));

        // Defer protocol-specific pipeline construction to the ALPN negotiation handler.
        // The h2 branch installs the frame codec and multiplex handler; the http/1.1
        // branch calls back into installHttp1Chain so the configurable
        // play.ssl.netty.pipeline chain stays the single source of truth for HTTP/1.1.
        pipeline.addLast("alpn", new Http2OrHttp1Negotiator(this::installHttp1Chain));
    }

    /**
     * Cached Netty {@link SslContext} shared across all accepted connections. Built lazily on
     * the first connection so the cert is loaded once per JVM, not per accept. {@code volatile}
     * + double-checked locking gives a happens-before guarantee without synchronizing every
     * channel-init under load. Cache is keyed by JVM lifetime; cert rotation requires a server
     * restart, matching the long-standing behavior of the prior JDK-engine path.
     */
    private static volatile SslContext cachedSslContext;

    /**
     * Build an {@link SslHandler} backed by a Netty {@link SslContext}. Supports both PEM
     * cert+key files ({@code certificate.file} / {@code certificate.key.file}) and JKS
     * keystores ({@code keystore.file} / {@code keystore.password}); the file-presence
     * precedence is the same one the legacy {@code SslHttpServerContextFactory} used.
     * ALPN is always configured (h2 + http/1.1) — see {@link #initChannel} for the rationale.
     */
    private SslHandler buildSslHandler(Channel ch) throws Exception {
        SslContext ctx = cachedSslContext;
        if (ctx == null) {
            synchronized (SslHttpServerPipelineFactory.class) {
                ctx = cachedSslContext;
                if (ctx == null) {
                    cachedSslContext = ctx = buildSslContext();
                }
            }
        }
        return ctx.newHandler(ch.alloc());
    }

    /**
     * Construct the {@link SslContext}. Visible to {@code SslHttpServerPipelineFactoryAlpnTest}
     * so tests can build an SslContext directly without standing up a Netty server.
     */
    static SslContext buildSslContext() throws Exception {
        Properties p = Play.configuration;
        File certFile = Play.getFile(p.getProperty("certificate.file", "conf/host.cert"));
        File keyFile = Play.getFile(p.getProperty("certificate.key.file", "conf/host.key"));

        SslContextBuilder builder;
        if (certFile.exists() && keyFile.exists()) {
            // PEM path. Netty's SslContextBuilder reads PEM cert+key directly — no need
            // for the BouncyCastle dance the legacy JDK-engine path used.
            builder = SslContextBuilder.forServer(certFile, keyFile);
        } else {
            // JKS path. Load the configured keystore and wrap a KeyManagerFactory.
            File keystoreFile = Play.getFile(p.getProperty("keystore.file", "conf/certificate.jks"));
            if (!keystoreFile.exists()) {
                throw new IllegalStateException(
                        "No HTTPS cert source found. Configure either PEM (certificate.file + "
                                + "certificate.key.file) or JKS (keystore.file). Looked for cert at "
                                + certFile.getAbsolutePath()
                                + ", key at " + keyFile.getAbsolutePath()
                                + ", keystore at " + keystoreFile.getAbsolutePath() + ".");
            }
            char[] password = p.getProperty("keystore.password", "secret").toCharArray();
            KeyStore ks = KeyStore.getInstance(p.getProperty("keystore.algorithm", "JKS"));
            try (FileInputStream in = new FileInputStream(keystoreFile)) {
                ks.load(in, password);
            }
            String kmfAlg = java.security.Security.getProperty("ssl.KeyManagerFactory.algorithm");
            if (kmfAlg == null) kmfAlg = "SunX509";
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(kmfAlg);
            kmf.init(ks, password);
            builder = SslContextBuilder.forServer(kmf);
        }

        builder.applicationProtocolConfig(new ApplicationProtocolConfig(
                Protocol.ALPN,
                // NO_ADVERTISE: if no overlap with client's ALPN list, fail handshake with no_application_protocol alert.
                SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT: even if the negotiated protocol isn't one we explicitly listed,
                // accept it (defensive — this combination effectively never fires here).
                SelectedListenerFailureBehavior.ACCEPT,
                ALPN_PROTOCOLS));

        // Per-connection knobs that the legacy JDK-engine path applied via SSLEngine setters,
        // now lifted to SslContext-build time so a single cached SslContext serves every accept.
        String mode = p.getProperty("play.netty.clientAuth", "none");
        if ("want".equalsIgnoreCase(mode)) {
            builder.clientAuth(ClientAuth.OPTIONAL);
        } else if ("need".equalsIgnoreCase(mode)) {
            builder.clientAuth(ClientAuth.REQUIRE);
        }

        String enabledCiphers = p.getProperty("play.ssl.enabledCiphers", "");
        if (enabledCiphers.length() > 0) {
            builder.ciphers(Arrays.asList(enabledCiphers.replaceAll(" ", "").split(",")));
        }

        String enabledProtocols = p.getProperty("play.ssl.enabledProtocols", "");
        if (enabledProtocols.trim().length() > 0) {
            builder.protocols(enabledProtocols.replaceAll(" ", "").split(","));
        }

        return builder.build();
    }

    /**
     * Build the HTTP/1.1 SSL pipeline chain by reflectively resolving the comma-separated
     * handler list in {@code play.ssl.netty.pipeline}. Extracted from {@link #initChannel}
     * so both the no-ALPN path and {@link Http2OrHttp1Negotiator}'s http/1.1 fallback hit
     * the same code. Propagates exceptions from {@link #sslGetInstance} the same way the
     * original inline construction did, so a misconfigured pipeline tears down the
     * connection rather than producing an incomplete handler chain.
     */
    private void installHttp1Chain(ChannelPipeline pipeline) throws Exception {
        String[] handlers = pipelineConfig.split(",");
        if (handlers.length <= 0) {
            Logger.error("You must defined at least the SslPlayHandler in \"play.netty.pipeline\"");
            return;
        }

        // PF-50: trim FQCNs before resolution; the previous code only trimmed for getName() but
        // not for the Class.forName() lookup, so a leading space in the comma-separated list
        // killed pipeline construction.
        // Create the play Handler (always the last one)
        String handler = handlers[handlers.length - 1].trim();
        ChannelHandler instance = sslGetInstance(handler);
        SslPlayHandler sslPlayHandler = (SslPlayHandler) instance;
        if (instance == null || !(instance instanceof SslPlayHandler) || sslPlayHandler == null) {
            Logger.error("The last handler must be the SslPlayHandler in \"play.netty.pipeline\"");
            return;
        }

        for (int i = 0; i < handlers.length - 1; i++) {
            handler = handlers[i].trim();
            try {
                String name = getName(handler);
                instance = sslGetInstance(handler);
                if (instance != null) {
                    pipeline.addLast(name, instance);
                    sslPlayHandler.pipelines.put("Ssl" + name, instance);
                }
            } catch (Throwable e) {
                // Throwable-first overload — the (String, Object...) form treats e as a {}
                // placeholder arg and drops the stack trace. See HttpServerPipelineFactory:144.
                Logger.error(e, " error adding %s", handler);
            }
        }

        pipeline.addLast("handler", sslPlayHandler);
        sslPlayHandler.pipelines.put("SslHandler", sslPlayHandler);
    }

    /** Server's ALPN preference order: h2 first, http/1.1 fallback. */
    static final String[] ALPN_PROTOCOLS = {"h2", "http/1.1"};

    private ChannelHandler sslGetInstance(String name) throws Exception {
        Class<?> clazz = classes.computeIfAbsent(name, className -> {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new play.exceptions.UnexpectedException(e);
            }
        });
        if (!ChannelHandler.class.isAssignableFrom(clazz)) return null;
        if (clazz == HttpObjectAggregator.class) {
            return new HttpObjectAggregator(DEFAULT_AGGREGATOR_MAX);
        }
        // Audit M32: HttpContentCompressor has no no-arg ctor in Netty 4.2, so the
        // generic fallback below silently failed to instantiate. Use the shared
        // builder to get the same gzip+deflate+brotli+zstd encoder set the plain
        // HTTP pipeline gets.
        if (clazz == io.netty.handler.codec.http.HttpContentCompressor.class) {
            return play.server.HttpServerPipelineFactory.buildHttpContentCompressor();
        }
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            return (ChannelHandler) ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
            Constructor<?> ctor = clazz.getDeclaredConstructor(int.class);
            return (ChannelHandler) ctor.newInstance(DEFAULT_AGGREGATOR_MAX);
        }
    }
}
