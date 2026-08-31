package play.server;

import java.lang.reflect.Field;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.compression.DeflateOptions;
import io.netty.handler.codec.compression.GzipOptions;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.HttpContentCompressor;

import play.Play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * PF-173: {@link HttpServerPipelineFactory#buildHttpContentCompressor()} must resolve which
 * optional codecs are available once per JVM, not once per pipeline.
 *
 * <p>Why it mattered: the previous shape probed by calling
 * {@code StandardCompressionOptions.zstd()} and catching the failure. With zstd-jni off the
 * classpath, {@code ZstdOptions}'s static initializer fails, the JVM marks the class erroneous,
 * and every subsequent call re-throws a fresh {@code NoClassDefFoundError} with a full stack
 * capture. Because a pipeline is built per accepted connection, that cost tracked connection
 * churn and never tapered off. JFR on a JClaw 0.18.32 run caught 58 throws across 500 requests
 * on a connection-churning load test vs. 4 on a slower run that reused connections.
 *
 * <p>The bug is invisible to a functional assertion — compression still falls back to
 * gzip/deflate correctly, so nothing observable over HTTP changes. What does change is object
 * identity: under the old code each call minted fresh {@code GzipOptions}/{@code DeflateOptions};
 * under the fix every compressor is handed the same resolved array. So these tests assert on the
 * options instances reachable from two separately built compressors, going through the real
 * public entry point rather than the test-only accessor (which would pass trivially if someone
 * reverted the method body to per-call resolution and left the accessor behind).
 */
class HttpContentCompressorCacheTest {

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

    /**
     * Reads one of Netty's private per-codec option fields. This couples to
     * {@link HttpContentCompressor}'s internals because there is no public accessor for what a
     * compressor was configured with — and the whole point of PF-173 is a property of the
     * configuration objects, not of the bytes on the wire. Netty gets bumped often enough here
     * that a rename should say so plainly rather than surface as a bare NoSuchFieldException.
     */
    private static Object optionsField(HttpContentCompressor compressor, String name) throws Exception {
        Field f;
        try {
            f = HttpContentCompressor.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Netty's HttpContentCompressor no longer declares '" + name
                    + "' — its field layout changed; update this test against the new Netty version", e);
        }
        f.setAccessible(true);
        return f.get(compressor);
    }

    @Test
    void codecAvailabilityIsResolvedOnceAndSharedAcrossCompressors() throws Exception {
        HttpContentCompressor first = HttpServerPipelineFactory.buildHttpContentCompressor();
        HttpContentCompressor second = HttpServerPipelineFactory.buildHttpContentCompressor();

        // Same option instances in both => the array was resolved once and reused. Re-running
        // the probe per call would hand each compressor its own freshly constructed options.
        for (String field : new String[] {"gzipOptions", "deflateOptions", "brotliOptions", "zstdOptions"}) {
            assertSame(optionsField(first, field), optionsField(second, field),
                    field + " differs between compressors — codec options are being rebuilt per pipeline");
        }
    }

    @Test
    void compressorItselfStaysPerPipeline() {
        // HttpContentCompressor extends HttpContentEncoder, which holds a per-channel
        // ChannelHandlerContext and encoder queue and is not @Sharable. Only the options are
        // hoistable; caching the handler would corrupt state across connections.
        HttpContentCompressor first = HttpServerPipelineFactory.buildHttpContentCompressor();
        HttpContentCompressor second = HttpServerPipelineFactory.buildHttpContentCompressor();
        assertNotSame(first, second, "compressor must not be shared between pipelines");
        assertNull(first.getClass().getAnnotation(io.netty.channel.ChannelHandler.Sharable.class),
                "HttpContentCompressor gained @Sharable — revisit whether it can now be hoisted");
    }

    @Test
    void gzipAndDeflateAreAlwaysWired() throws Exception {
        HttpContentCompressor compressor = HttpServerPipelineFactory.buildHttpContentCompressor();
        assertNotNull(optionsField(compressor, "gzipOptions"), "gzip must always be enabled");
        assertNotNull(optionsField(compressor, "deflateOptions"), "deflate must always be enabled");
    }

    /**
     * The optional codecs are classpath-dependent — brotli4j ships transitively with
     * netty-codec-compression, zstd-jni is opt-in and absent from {@code framework/lib} — so the
     * expectation is derived from Netty's own availability flags rather than hardcoded. This
     * catches an inverted guard or a codec silently dropping out of the set on either classpath.
     */
    @Test
    void optionalCodecsMatchNettyReportedAvailability() throws Exception {
        HttpContentCompressor compressor = HttpServerPipelineFactory.buildHttpContentCompressor();

        if (Brotli.isAvailable()) {
            assertNotNull(optionsField(compressor, "brotliOptions"),
                    "brotli4j is available but brotli was not wired");
        } else {
            assertNull(optionsField(compressor, "brotliOptions"),
                    "brotli was wired despite brotli4j being unavailable");
        }

        if (Zstd.isAvailable()) {
            assertNotNull(optionsField(compressor, "zstdOptions"),
                    "zstd-jni is available but zstd was not wired");
        } else {
            assertNull(optionsField(compressor, "zstdOptions"),
                    "zstd was wired despite zstd-jni being unavailable");
        }
    }

    @Test
    void resolvedOptionSetIsWellFormed() {
        CompressionOptions[] opts = HttpServerPipelineFactory.compressionOptions();
        assertSame(opts, HttpServerPipelineFactory.compressionOptions(),
                "options array must be resolved once, not rebuilt per access");

        int gzip = 0, deflate = 0;
        for (CompressionOptions o : opts) {
            assertNotNull(o, "null entry in options array would trip Netty's deepCheckNotNull");
            // GzipOptions extends DeflateOptions, so order the checks accordingly.
            if (o instanceof GzipOptions) gzip++;
            else if (o instanceof DeflateOptions) deflate++;
        }
        assertEquals(1, gzip, "expected exactly one GzipOptions");
        assertEquals(1, deflate, "expected exactly one DeflateOptions");
    }
}
