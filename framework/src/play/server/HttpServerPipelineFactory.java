package play.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import play.Play;
import play.Logger;
import play.exceptions.UnexpectedException;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * HttpContentCompressor tunables. Read once at class load (Play.configuration is populated
     * before Server.start() instantiates this factory). Defaults track Netty/zlib/brotli library
     * defaults so this is purely additive — existing deployments behave identically. Out-of-range
     * values are clamped rather than thrown, since a misconfigured level shouldn't blow up the
     * pipeline init for every accepted connection.
     *
     * <p>Tests that mutate {@link Play#configuration} after class load will not see the new
     * values; functional tests that boot a fresh Play instance per {@code %test.} config block
     * pick them up correctly because the JVM reloads the class.</p>
     */
    private static final int COMPRESSION_THRESHOLD = intConfigClamped(
            "play.netty.compression.contentSizeThreshold", 0, 0, Integer.MAX_VALUE);
    private static final int GZIP_LEVEL = intConfigClamped(
            "play.netty.compression.gzip.level", 6, 1, 9);
    private static final int DEFLATE_LEVEL = intConfigClamped(
            "play.netty.compression.deflate.level", 6, 1, 9);
    private static final int BROTLI_QUALITY = intConfigClamped(
            "play.netty.compression.brotli.quality", 4, 0, 11);

    private static int intConfig(String key, int defaultValue) {
        String raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            Logger.warn("Invalid value for %s='%s'; using default %d", key, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Read an int config value and clamp it to {@code [lo, hi]}. Emits a WARN line whenever a
     * configured value falls outside the supported range so an operator who set
     * {@code brotli.quality = 99} sees that they actually got q=11 — matching the behavior
     * documented in the application-skel template ("Out-of-range values are clamped to the
     * supported range and logged at WARN").
     */
    private static int intConfigClamped(String key, int defaultValue, int lo, int hi) {
        int v = intConfig(key, defaultValue);
        if (v < lo) {
            Logger.warn("%s=%d below minimum %d; clamping to %d", key, v, lo, lo);
            return lo;
        }
        if (v > hi) {
            Logger.warn("%s=%d above maximum %d; clamping to %d", key, v, hi, hi);
            return hi;
        }
        return v;
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
                // Play's Logger has overloads (String, Object...) and (Throwable, String, Object...).
                // The throwable-first form is the one that prints the stack trace; the other formats
                // the throwable as a {} placeholder arg, swallowing the diagnostic.
                Logger.error(e, " error adding " + handler);
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

        // HttpContentCompressor: the no-arg ctor in Netty 4.2 only enables gzip + deflate. We
        // construct it with explicit CompressionOptions so brotli (and zstd) auto-enable when
        // their native libs are on the classpath. brotli4j ships transitively with
        // netty-codec-compression; zstd-jni is opt-in. StandardCompressionOptions.brotli() /
        // .zstd() throw at call time if the native deps are absent — catch and skip.
        if (clazz == HttpContentCompressor.class) {
            List<CompressionOptions> opts = new ArrayList<>(4);
            // gzip/deflate take (level, windowBits, memLevel); we expose only level — the other
            // two are zlib trivia (windowBits=15 → 32KB window; memLevel=8 → 256KB state) that
            // nobody benchmarks against in HTTP contexts.
            opts.add(StandardCompressionOptions.gzip(GZIP_LEVEL, 15, 8));
            opts.add(StandardCompressionOptions.deflate(DEFLATE_LEVEL, 15, 8));
            // brotli4j Encoder.Parameters is referenced by FQCN inside the try block so the JVM
            // resolves it at method-call time rather than at HttpServerPipelineFactory class load.
            // If brotli4j is absent, NoClassDefFoundError is caught and brotli is skipped.
            try {
                com.aayushatharva.brotli4j.encoder.Encoder.Parameters brotliParams =
                        new com.aayushatharva.brotli4j.encoder.Encoder.Parameters().setQuality(BROTLI_QUALITY);
                opts.add(StandardCompressionOptions.brotli(brotliParams));
            } catch (Throwable ignored) { /* brotli4j absent */ }
            try { opts.add(StandardCompressionOptions.zstd()); } catch (Throwable ignored) { /* zstd-jni absent */ }
            return new HttpContentCompressor(COMPRESSION_THRESHOLD, opts.toArray(new CompressionOptions[0]));
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
