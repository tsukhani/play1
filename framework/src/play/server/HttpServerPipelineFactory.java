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
import java.util.concurrent.ConcurrentHashMap;

public class HttpServerPipelineFactory extends ChannelInitializer<Channel> {

    // PF-49: ConcurrentHashMap so concurrent initChannel calls (one per accepted connection)
    // don't corrupt the cache via the previously unsynchronized HashMap.computeIfAbsent path.
    protected static final Map<String, Class<?>> classes = new ConcurrentHashMap<>();

    /**
     * Default Netty 4 pipeline. {@link StreamChunkAggregator} aggregates request fragments
     * (HttpRequest + HttpContent + LastHttpContent) into a FullHttpRequest, spooling large
     * bodies to disk past {@code play.netty.spoolThresholdBytes} (default 1 MB) and
     * enforcing the optional {@code play.netty.maxContentLength} hard cap.
     */
    private final String pipelineConfig = Play.configuration.getProperty("play.netty.pipeline",
            "io.netty.handler.codec.http.HttpRequestDecoder,play.server.StreamChunkAggregator,io.netty.handler.codec.http.HttpResponseEncoder,io.netty.handler.stream.ChunkedWriteHandler,play.server.PlayHandler");

    /**
     * Fallback for stock {@link HttpObjectAggregator} if a user wires it explicitly.
     *
     * <p>PF-65: the documented "unlimited" sentinel for {@code play.netty.maxContentLength} is
     * {@code -1}, which our own {@link StreamChunkAggregator} interprets correctly. Netty's
     * {@code HttpObjectAggregator} ctor however rejects any negative value
     * ({@code IllegalArgumentException: "maxContentLength must be a non-negative number"}),
     * causing the user's explicitly-wired aggregator to fail construction and silently fall out
     * of the pipeline. Promote any negative value to {@link Integer#MAX_VALUE} so the unlimited
     * semantic carries through without breaking the handler.
     */
    private static final int DEFAULT_AGGREGATOR_MAX = sanitizeAggregatorMax(
            Play.configuration.getProperty("play.netty.maxContentLength", "1048576"));

    private static int sanitizeAggregatorMax(String configured) {
        int v;
        try {
            v = Integer.parseInt(configured);
        } catch (NumberFormatException nfe) {
            v = 1048576;
        }
        return v < 0 ? Integer.MAX_VALUE : v;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        String[] handlers = pipelineConfig.split(",");
        if (handlers.length <= 0) {
            Logger.error("You must defined at least the playHandler in \"play.netty.pipeline\"");
            return;
        }

        // PF-50: trim each comma-separated FQCN before lookup. Without trim, a config like
        // "io.netty.handler.codec.http.HttpRequestDecoder, play.server.PlayHandler" fails to
        // resolve the second class because Class.forName(" play.server.PlayHandler") throws.
        // Create the play Handler (always the last one)
        String handler = handlers[handlers.length - 1].trim();
        ChannelHandler instance = getInstance(handler);
        PlayHandler playHandler = (PlayHandler) instance;
        if (playHandler == null) {
            Logger.error("The last handler must be the playHandler in \"play.netty.pipeline\"");
            return;
        }

        // Build the pipeline. Users can extend it via play.netty.pipeline config.
        for (int i = 0; i < handlers.length - 1; i++) {
            handler = handlers[i].trim();
            try {
                String name = getName(handler);
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

        // HttpObjectAggregator (when explicitly wired by users) requires a maxContentLength arg.
        if (clazz == HttpObjectAggregator.class) {
            return new HttpObjectAggregator(DEFAULT_AGGREGATOR_MAX);
        }

        // Otherwise prefer no-arg constructor; fall back to int-arg if needed.
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            return (ChannelHandler) ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
            Constructor<?> ctor = clazz.getDeclaredConstructor(int.class);
            return (ChannelHandler) ctor.newInstance(DEFAULT_AGGREGATOR_MAX);
        }
    }
}
