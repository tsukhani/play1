package play.server.ssl;

import javax.net.ssl.SSLEngine;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.SslHandler;

import java.lang.reflect.Constructor;

import play.Logger;
import play.Play;
import play.server.HttpServerPipelineFactory;

public class SslHttpServerPipelineFactory extends HttpServerPipelineFactory {

    private final String pipelineConfig = Play.configuration.getProperty("play.ssl.netty.pipeline",
            "io.netty.handler.codec.http.HttpRequestDecoder,play.server.StreamChunkAggregator,io.netty.handler.codec.http.HttpResponseEncoder,io.netty.handler.stream.ChunkedWriteHandler,play.server.ssl.SslPlayHandler");

    private static final int DEFAULT_AGGREGATOR_MAX = Integer.parseInt(
            Play.configuration.getProperty("play.netty.maxContentLength", "1048576"));

    @Override
    protected void initChannel(Channel ch) throws Exception {

        String mode = Play.configuration.getProperty("play.netty.clientAuth", "none");
        String enabledCiphers = Play.configuration.getProperty("play.ssl.enabledCiphers", "");
        String enabledProtocols = Play.configuration.getProperty("play.ssl.enabledProtocols", "");

        ChannelPipeline pipeline = ch.pipeline();

        // Add SSL handler first to encrypt and decrypt everything.
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

        engine.setEnableSessionCreation(true);

        pipeline.addLast("ssl", new SslHandler(engine));

        // Build the rest of the pipeline. Users can extend it via play.ssl.netty.pipeline.
        String[] handlers = pipelineConfig.split(",");
        if (handlers.length <= 0) {
            Logger.error("You must defined at least the SslPlayHandler in \"play.netty.pipeline\"");
            return;
        }

        // Create the play Handler (always the last one)
        String handler = handlers[handlers.length - 1];
        ChannelHandler instance = sslGetInstance(handler);
        SslPlayHandler sslPlayHandler = (SslPlayHandler) instance;
        if (instance == null || !(instance instanceof SslPlayHandler) || sslPlayHandler == null) {
            Logger.error("The last handler must be the SslPlayHandler in \"play.netty.pipeline\"");
            return;
        }

        for (int i = 0; i < handlers.length - 1; i++) {
            handler = handlers[i];
            try {
                String name = getName(handler.trim());
                instance = sslGetInstance(handler);
                if (instance != null) {
                    pipeline.addLast(name, instance);
                    sslPlayHandler.pipelines.put("Ssl" + name, instance);
                }
            } catch (Throwable e) {
                Logger.error(" error adding " + handler, e);
            }
        }

        pipeline.addLast("handler", sslPlayHandler);
        sslPlayHandler.pipelines.put("SslHandler", sslPlayHandler);
    }

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
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            return (ChannelHandler) ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
            Constructor<?> ctor = clazz.getDeclaredConstructor(int.class);
            return (ChannelHandler) ctor.newInstance(DEFAULT_AGGREGATOR_MAX);
        }
    }
}
