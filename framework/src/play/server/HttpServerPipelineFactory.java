package play.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import play.Play;
import play.Logger;
import play.exceptions.UnexpectedException;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.HashMap;

public class HttpServerPipelineFactory extends ChannelInitializer<Channel> {

    protected static final Map<String, Class<?>> classes = new HashMap<>();

    /**
     * Default Netty 4 pipeline. {@code HttpObjectAggregator} aggregates request
     * fragments (HttpRequest + HttpContent + LastHttpContent) into a
     * {@code FullHttpRequest}. The 1 MB cap is a temporary Stage A limit;
     * Stage B (PF-32) restores disk-spooling for large bodies.
     */
    private final String pipelineConfig = Play.configuration.getProperty("play.netty.pipeline",
            "io.netty.handler.codec.http.HttpRequestDecoder,io.netty.handler.codec.http.HttpObjectAggregator,io.netty.handler.codec.http.HttpResponseEncoder,io.netty.handler.stream.ChunkedWriteHandler,play.server.PlayHandler");

    /** Cap aggregated request body at 1 MB during Stage A. */
    private static final int DEFAULT_MAX_CONTENT_LENGTH = Integer.parseInt(
            Play.configuration.getProperty("play.netty.maxContentLength", "1048576"));

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        String[] handlers = pipelineConfig.split(",");
        if (handlers.length <= 0) {
            Logger.error("You must defined at least the playHandler in \"play.netty.pipeline\"");
            return;
        }

        // Create the play Handler (always the last one)
        String handler = handlers[handlers.length - 1];
        ChannelHandler instance = getInstance(handler);
        PlayHandler playHandler = (PlayHandler) instance;
        if (playHandler == null) {
            Logger.error("The last handler must be the playHandler in \"play.netty.pipeline\"");
            return;
        }

        // Build the pipeline. Users can extend it via play.netty.pipeline config.
        for (int i = 0; i < handlers.length - 1; i++) {
            handler = handlers[i];
            try {
                String name = getName(handler.trim());
                instance = getInstance(handler);
                if (instance != null) {
                    pipeline.addLast(name, instance);
                    playHandler.pipelines.put(name, instance);
                }
            } catch (Throwable e) {
                Logger.error(" error adding " + handler, e);
            }
        }

        pipeline.addLast("handler", playHandler);
        playHandler.pipelines.put("handler", playHandler);
    }

    protected String getName(String name) {
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0)
            return name.substring(dotIndex + 1);
        return name;
    }

    protected ChannelHandler getInstance(String name) throws Exception {
        Class<?> clazz = classes.computeIfAbsent(name, className -> {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new UnexpectedException(e);
            }
        });
        if (!ChannelHandler.class.isAssignableFrom(clazz)) return null;

        // HttpObjectAggregator requires a maxContentLength constructor arg.
        if (clazz == HttpObjectAggregator.class) {
            return new HttpObjectAggregator(DEFAULT_MAX_CONTENT_LENGTH);
        }

        // Otherwise prefer no-arg constructor; fall back to int-arg if needed.
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            return (ChannelHandler) ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
            Constructor<?> ctor = clazz.getDeclaredConstructor(int.class);
            return (ChannelHandler) ctor.newInstance(DEFAULT_MAX_CONTENT_LENGTH);
        }
    }
}
