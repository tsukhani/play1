package play.server;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import play.Play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PF-257: the compressor that {@link HttpServerPipelineFactory#buildHttpContentCompressor()} builds
 * leaves a close-delimited body ({@link PlayHandler#frameStream}) uncompressed. Netty's encoder
 * frames a compressed body of unknown length chunked, which an HTTP/1.0 client must never get and
 * which, on a connection the server closes, is what let the client hang up first.
 */
class CloseDelimitedCompressionTest {

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
    void aCloseDelimitedStreamPassesUncompressed() {
        HttpResponse head = streamHead(false);
        HttpResponse out = encode(head);
        assertNull(out.headers().get(HttpHeaderNames.CONTENT_ENCODING), "close-delimited: not compressed");
        assertNull(out.headers().get(HttpHeaderNames.TRANSFER_ENCODING), "close-delimited: not chunked");
        assertFalse(out.headers().contains(HttpHeaderNames.CONTENT_LENGTH));
    }

    /** The pass-through is for close-delimited bodies only: a kept-alive stream is compressed as before. */
    @Test
    void aKeptAliveStreamIsStillCompressed() {
        HttpResponse out = encode(streamHead(true));
        assertEquals("gzip", out.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertEquals("chunked", out.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
    }

    /** A streamed response head as copyResponse frames it, on a connection kept alive or closed after it. */
    private static HttpResponse streamHead(boolean keepAlive) {
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        if (!keepAlive) {
            PlayHandler.closesAfter(head);
        }
        PlayHandler.frameStream(head, keepAlive);
        return head;
    }

    private static HttpResponse encode(HttpResponse head) {
        EmbeddedChannel channel = new EmbeddedChannel(HttpServerPipelineFactory.buildHttpContentCompressor());
        try {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/sse");
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
            channel.writeInbound(request);
            ReferenceCountUtil.release(channel.readInbound());
            channel.writeOutbound(head,
                    new DefaultHttpContent(Unpooled.copiedBuffer("data: {\"i\":0}\n\n", StandardCharsets.US_ASCII)),
                    LastHttpContent.EMPTY_LAST_CONTENT);
            HttpResponse out = channel.readOutbound();
            for (Object o; (o = channel.readOutbound()) != null; ) {
                ReferenceCountUtil.release(o);
            }
            return out;
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
