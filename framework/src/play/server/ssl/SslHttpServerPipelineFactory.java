package play.server.ssl;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.security.KeyStore;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
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

        String mode = Play.configuration.getProperty("play.netty.clientAuth", "none");
        String enabledCiphers = Play.configuration.getProperty("play.ssl.enabledCiphers", "");
        String enabledProtocols = Play.configuration.getProperty("play.ssl.enabledProtocols", "");

        ChannelPipeline pipeline = ch.pipeline();

        boolean http2Enabled = isHttp2Enabled();

        // PF-58: when HTTP/2 is enabled, build a Netty SslContext with explicit ALPN config
        // instead of going through the JDK SSLEngine path. The JDK SSLEngine's ALPN selection
        // on JDK 25 is implementation-defined when both sides offer overlapping protocols and
        // empirically prefers the client's ordering, which silently downgrades h2 to http/1.1
        // even when both sides support h2. Netty's SslContext does deterministic server-preferred
        // selection. Requires PEM cert+key files (the keystore.jks path is unchanged from before
        // PF-58 and does not get ALPN — JKS users wanting h2 must convert to PEM).
        SslHandler sslHandler;
        if (http2Enabled) {
            sslHandler = buildHttp2SslHandler(ch);
        } else {
            SSLEngine engine = SslHttpServerContextFactory.getServerContext().createSSLEngine();
            engine.setUseClientMode(false);

            if (enabledCiphers != null && enabledCiphers.length() > 0) {
                engine.setEnabledCipherSuites(enabledCiphers.replaceAll(" ", "").split(","));
            }

            if ("want".equalsIgnoreCase(mode)) {
                engine.setWantClientAuth(true);
            } else if ("need".equalsIgnoreCase(mode)) {
                engine.setNeedClientAuth(true);
            }

            if (enabledProtocols != null && enabledProtocols.trim().length() > 0) {
                engine.setEnabledProtocols(enabledProtocols.replaceAll(" ", "").split(","));
            }
            // (Note: setEnableSessionCreation(true) is the JDK default; calling it explicitly is
            // a no-op and was removed.)
            sslHandler = new SslHandler(engine);
        }

        pipeline.addLast("ssl", sslHandler);

        // PF-58: with HTTP/2 enabled, defer protocol-specific pipeline construction to the
        // ALPN negotiation handler. The h2 branch installs the frame codec and multiplex
        // handler; the http/1.1 branch calls back into installHttp1Chain so the configurable
        // play.ssl.netty.pipeline chain stays the single source of truth for HTTP/1.1.
        if (http2Enabled) {
            pipeline.addLast("alpn", new Http2OrHttp1Negotiator(this::installHttp1Chain));
            return;
        }

        installHttp1Chain(pipeline);
    }

    /**
     * Cached Netty {@link SslContext} for the HTTP/2 path. Built lazily on first connection so
     * the cert is loaded once per JVM, not once per accepted connection. {@code volatile} +
     * double-checked locking gives us a happens-before guarantee without synchronizing every
     * channel-init under load.
     */
    private static volatile SslContext http2SslContext;

    /**
     * Build an {@link SslHandler} backed by a Netty {@link SslContext} with ALPN configured.
     * Supports both PEM cert+key files ({@code certificate.file} / {@code certificate.key.file})
     * and JKS keystores ({@code keystore.file} / {@code keystore.password}). Mirrors the file-presence
     * detection that {@link SslHttpServerContextFactory} uses for the JDK-engine path.
     *
     * <p>The {@link SslContext} is cached statically — building it parses the cert + key + DH
     * parameters and is the order of milliseconds; doing it per accepted connection would be a
     * real cost under load. The cache is keyed only by JVM lifetime; cert rotation requires a
     * server restart (same as the existing JDK SSL context, which is also static-init only).
     */
    private SslHandler buildHttp2SslHandler(Channel ch) throws Exception {
        SslContext ctx = http2SslContext;
        if (ctx == null) {
            synchronized (SslHttpServerPipelineFactory.class) {
                ctx = http2SslContext;
                if (ctx == null) {
                    http2SslContext = ctx = buildHttp2SslContext();
                }
            }
        }
        return ctx.newHandler(ch.alloc());
    }

    private static SslContext buildHttp2SslContext() throws Exception {
        Properties p = Play.configuration;
        File certFile = Play.getFile(p.getProperty("certificate.file", "conf/host.cert"));
        File keyFile = Play.getFile(p.getProperty("certificate.key.file", "conf/host.key"));

        ApplicationProtocolConfig apn = new ApplicationProtocolConfig(
                Protocol.ALPN,
                // NO_ADVERTISE: if no overlap with client's ALPN list, fail handshake with no_application_protocol alert.
                SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT: even if the negotiated protocol isn't one we explicitly listed,
                // accept it (defensive — this combination effectively never fires here).
                SelectedListenerFailureBehavior.ACCEPT,
                ALPN_PROTOCOLS);

        // PEM path: both cert + key files present. Mirrors SslHttpServerContextFactory's
        // file-presence check so PEM users see the same precedence on both code paths.
        if (certFile.exists() && keyFile.exists()) {
            return SslContextBuilder.forServer(certFile, keyFile)
                    .applicationProtocolConfig(apn)
                    .build();
        }

        // JKS path: load the configured keystore and wrap a KeyManagerFactory. Defaults match
        // SslHttpServerContextFactory exactly so an existing keystore-using deployment can flip
        // play.http2.enabled=true without re-configuring cert paths.
        File keystoreFile = Play.getFile(p.getProperty("keystore.file", "conf/certificate.jks"));
        if (!keystoreFile.exists()) {
            throw new IllegalStateException(
                    "PF-58: play.http2.enabled=true but no cert source found. Configure either "
                            + "PEM (certificate.file + certificate.key.file) or JKS (keystore.file). "
                            + "Looked for cert at " + certFile.getAbsolutePath()
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
        return SslContextBuilder.forServer(kmf)
                .applicationProtocolConfig(apn)
                .build();
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

    /**
     * Read the {@code play.http2.enabled} flag. Package-private so {@code SslHttpServerPipelineFactoryAlpnTest}
     * can verify the gate without spinning up the full SSL context factory (which requires cert files).
     */
    static boolean isHttp2Enabled() {
        return Boolean.parseBoolean(Play.configuration.getProperty("play.http2.enabled", "false"));
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
