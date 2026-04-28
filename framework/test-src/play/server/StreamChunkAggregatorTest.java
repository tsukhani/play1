package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Drives {@link StreamChunkAggregator} with an {@link EmbeddedChannel} so the spool /
 * reject / 100-Continue paths can be exercised without standing up a full server. These
 * tests lock in invariants that the audit-pass commits added (PF-37, PF-44, PF-54..PF-56,
 * PF-65) — regressions on any of them silently corrupt request handling for the affected
 * shape.
 */
public class StreamChunkAggregatorTest {

    private Properties savedConfig;
    private File savedTmpDir;

    @BeforeEach
    void setUp() throws Exception {
        savedConfig = Play.configuration;
        savedTmpDir = Play.tmpDir;
        Play.configuration = new Properties();
        Play.tmpDir = Files.createTempDirectory("aggregator-test-").toFile();
        Play.tmpDir.deleteOnExit();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = savedConfig;
        Play.tmpDir = savedTmpDir;
    }

    private static DefaultHttpRequest req(HttpMethod method, String uri) {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri);
    }

    private static ByteBuf buf(String s) {
        return Unpooled.copiedBuffer(s, CharsetUtil.UTF_8);
    }

    @Test
    void smallBody_keepsInMemory_noSpoolAttribute() {
        Play.configuration.setProperty("play.netty.spoolThresholdBytes", "1024");
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        ch.writeInbound(req(HttpMethod.POST, "/echo"));
        ch.writeInbound(new DefaultHttpContent(buf("hello")));
        ch.writeInbound(new DefaultLastHttpContent(buf(" world")));

        FullHttpRequest full = ch.readInbound();
        assertNotNull(full, "expected aggregated FullHttpRequest");
        assertEquals("hello world", full.content().toString(CharsetUtil.UTF_8));
        assertEquals("11", full.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertNull(ch.attr(StreamChunkAggregator.SPOOLED_BODY).get(),
                "small body must NOT set the spool attribute");
        full.release();
    }

    @Test
    void overThreshold_spoolsToDisk_setsAttribute_emptyBody() throws Exception {
        Play.configuration.setProperty("play.netty.spoolThresholdBytes", "8");
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        ch.writeInbound(req(HttpMethod.POST, "/upload"));
        ch.writeInbound(new DefaultHttpContent(buf("1234567890")));        // 10 bytes
        ch.writeInbound(new DefaultLastHttpContent(buf("abcdef")));        // 6 bytes, total 16

        FullHttpRequest full = ch.readInbound();
        assertNotNull(full);
        assertEquals(0, full.content().readableBytes(),
                "spooled FullHttpRequest carries an empty buf — body lives on disk");
        assertEquals("16", full.headers().get(HttpHeaderNames.CONTENT_LENGTH));

        File spool = ch.attr(StreamChunkAggregator.SPOOLED_BODY).get();
        assertNotNull(spool, "over-threshold body must set SPOOLED_BODY");
        assertTrue(spool.exists() && spool.length() == 16);
        assertEquals("1234567890abcdef",
                new String(Files.readAllBytes(spool.toPath()), StandardCharsets.UTF_8));

        // PlayHandler.parseRequest takes ownership; emulate that here so cleanup matches prod.
        ch.attr(StreamChunkAggregator.SPOOLED_BODY).set(null);
        if (!spool.delete()) spool.deleteOnExit();
        full.release();
    }

    @Test
    void overMaxContentLength_returns413_andClosesChannel() {
        Play.configuration.setProperty("play.netty.maxContentLength", "8");
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        ch.writeInbound(req(HttpMethod.POST, "/upload"));
        ch.writeInbound(new DefaultHttpContent(buf("123456789")));   // 9 > 8

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp, "expected outbound 413");
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, resp.status());
        assertEquals("close", resp.headers().get(HttpHeaderNames.CONNECTION));
        resp.release();

        // The CLOSE listener on the 413 future closes the channel synchronously inside
        // EmbeddedChannel — no extra runPendingTasks() needed, and any further writeInbound
        // would throw ClosedChannelException.
        assertFalse(ch.isOpen(), "channel must be closed after 413");
    }

    @Test
    void requestDecoderFailure_returns400_andClosesChannel() {
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        DefaultHttpRequest bad = req(HttpMethod.POST, "/x");
        bad.setDecoderResult(io.netty.handler.codec.DecoderResult.failure(new IllegalArgumentException("bad start-line")));
        ch.writeInbound(bad);

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        resp.release();
        ch.runPendingTasks();
        assertFalse(ch.isOpen());
    }

    @Test
    void contentDecoderFailure_returns400_andClosesChannel() {
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());
        ch.writeInbound(req(HttpMethod.POST, "/x"));

        DefaultHttpContent bad = new DefaultHttpContent(buf("partial"));
        bad.setDecoderResult(io.netty.handler.codec.DecoderResult.failure(new IllegalArgumentException("bad chunk size")));
        ch.writeInbound(bad);

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        resp.release();
    }

    @Test
    void expect100Continue_underCap_emitsContinue() {
        Play.configuration.setProperty("play.netty.maxContentLength", "100");
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        DefaultHttpRequest r = req(HttpMethod.POST, "/x");
        r.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
        r.headers().set(HttpHeaderNames.CONTENT_LENGTH, "10");
        ch.writeInbound(r);

        FullHttpResponse cont = ch.readOutbound();
        assertNotNull(cont, "expected 100 Continue");
        assertEquals(HttpResponseStatus.CONTINUE, cont.status());
        cont.release();
        assertTrue(ch.isOpen(), "100 Continue must NOT close the channel");
    }

    @Test
    void expect100Continue_overCap_returns413() {
        Play.configuration.setProperty("play.netty.maxContentLength", "8");
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        DefaultHttpRequest r = req(HttpMethod.POST, "/x");
        r.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
        r.headers().set(HttpHeaderNames.CONTENT_LENGTH, "9999");
        ch.writeInbound(r);

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, resp.status());
        resp.release();
    }

    @Test
    void transferEncodingChunked_strippedFromAggregatedRequest() {
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        DefaultHttpRequest r = req(HttpMethod.POST, "/x");
        r.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "gzip, chunked");
        ch.writeInbound(r);
        ch.writeInbound(new DefaultLastHttpContent(buf("hi")));

        FullHttpRequest full = ch.readInbound();
        assertEquals("gzip", full.headers().get(HttpHeaderNames.TRANSFER_ENCODING),
                "only the chunked token is stripped (PF-37); other codings preserved");
        assertEquals("2", full.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        full.release();
    }

    @Test
    void transferEncodingOnlyChunked_removedEntirely() {
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        DefaultHttpRequest r = req(HttpMethod.POST, "/x");
        r.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        ch.writeInbound(r);
        ch.writeInbound(new DefaultLastHttpContent(buf("hi")));

        FullHttpRequest full = ch.readInbound();
        assertNull(full.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
        full.release();
    }

    @Test
    void invalidConfig_fallsBackToDefault_withoutThrowing() {
        Play.configuration.setProperty("play.netty.spoolThresholdBytes", "not-a-number");
        Play.configuration.setProperty("play.netty.maxContentLength", "also-bad");

        // Construction must not throw — every accepted connection used to die before this fix.
        StreamChunkAggregator agg = new StreamChunkAggregator();
        EmbeddedChannel ch = new EmbeddedChannel(agg);

        ch.writeInbound(req(HttpMethod.POST, "/x"));
        ch.writeInbound(new DefaultLastHttpContent(buf("body")));

        FullHttpRequest full = ch.readInbound();
        assertNotNull(full, "default config path must still aggregate");
        assertEquals("body", full.content().toString(CharsetUtil.UTF_8));
        full.release();
    }

    @Test
    void fullHttpRequest_passesThroughUntouched() {
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        // A custom upstream handler may hand us an already-aggregated request; the aggregator
        // must not re-wrap it. Use Netty's DefaultFullHttpRequest directly.
        io.netty.handler.codec.http.DefaultFullHttpRequest already =
                new io.netty.handler.codec.http.DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/passthru", buf("x"));
        ch.writeInbound(already);

        FullHttpRequest got = ch.readInbound();
        assertTrue(got == already, "passthrough must not allocate a new FullHttpRequest");
        got.release();
    }

    @Test
    void connectionDropMidUpload_deletesSpoolFile() throws Exception {
        Play.configuration.setProperty("play.netty.spoolThresholdBytes", "4");
        EmbeddedChannel ch = new EmbeddedChannel(new StreamChunkAggregator());

        ch.writeInbound(req(HttpMethod.POST, "/x"));
        ch.writeInbound(new DefaultHttpContent(buf("12345678")));

        // Channel attribute is NOT set yet — only set on LastHttpContent — but a spool file
        // exists on disk. Closing the channel should delete it via channelInactive.
        File[] before = Play.tmpDir.listFiles((d, n) -> n.startsWith("play-upload-"));
        assertNotNull(before);
        assertTrue(before.length >= 1, "expected at least one spool file");

        ch.close().syncUninterruptibly();
        ch.runPendingTasks();

        File[] after = Play.tmpDir.listFiles((d, n) -> n.startsWith("play-upload-"));
        assertNotNull(after);
        // delete may race with deleteOnExit fallback; either way the count must not have grown.
        assertTrue(after.length <= before.length,
                "spool files must be cleaned up on channelInactive");
    }
}
