package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import play.Logger;
import play.Play;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Aggregates fragmented HTTP request bodies (HttpRequest + HttpContent + LastHttpContent)
 * into a {@link FullHttpRequest}. Small bodies stay in memory; bodies that exceed
 * {@code play.netty.spoolThresholdBytes} are spooled to a temp file and the channel
 * attribute {@link #SPOOLED_BODY} is set to point at the file. {@link PlayHandler#parseRequest}
 * reads the attribute to switch between in-memory and file-backed body input streams.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code play.netty.spoolThresholdBytes} — bytes; bodies over this threshold are
 *       spooled to disk. Default 1 MB.</li>
 *   <li>{@code play.netty.maxContentLength} — bytes; hard cap on body size. {@code -1} (default)
 *       means unlimited. Requests exceeding the cap are rejected with 413 Request Entity Too Large.</li>
 * </ul>
 *
 * <p>Replaces the Netty 3-coupled aggregator deleted in PF-31. PF-33 will revisit
 * temp-file lifecycle when WebSocket support comes back.
 */
public class StreamChunkAggregator extends ChannelInboundHandlerAdapter {

    /**
     * Channel attribute set to the spooled body file for the current request.
     * {@link PlayHandler#parseRequest} reads via {@code getAndSet(null)} to atomically
     * read-and-clear, which is safe under HTTP/1.1 keep-alive request serialization.
     */
    public static final AttributeKey<File> SPOOLED_BODY = AttributeKey.valueOf("play.spooledBody");

    private static final int DEFAULT_SPOOL_THRESHOLD = 1 * 1024 * 1024; // 1 MB

    private final int spoolThresholdBytes;
    private final long maxContentLength;

    private HttpRequest pendingRequest;
    private CompositeByteBuf inMemoryBody;
    private File spoolFile;
    private OutputStream spoolOut;
    private long bytesReceived;
    private boolean overflow;
    private boolean rejected;

    public StreamChunkAggregator() {
        // Defensive parse: this ctor is invoked once per accepted connection (channelInitializer
        // path). A typo in either property would otherwise throw NumberFormatException for every
        // connection, so the server appears to "drop all traffic" with no useful log. Fall back
        // to documented defaults and warn loudly so the operator notices.
        this.spoolThresholdBytes = parseIntConfig(
                "play.netty.spoolThresholdBytes", DEFAULT_SPOOL_THRESHOLD);
        this.maxContentLength = parseLongConfig(
                "play.netty.maxContentLength", -1L);
    }

    private static int parseIntConfig(String key, int defaultValue) {
        String raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            Logger.warn("Invalid %s='%s'; using default %d", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static long parseLongConfig(String key, long defaultValue) {
        String raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException nfe) {
            Logger.warn("Invalid %s='%s'; using default %d", key, raw, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Pass through anything that isn't an HTTP request fragment (e.g., already-aggregated
        // FullHttpRequest from a custom upstream handler).
        if (!(msg instanceof HttpRequest) && !(msg instanceof HttpContent)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // FullHttpRequest is itself an HttpRequest — already aggregated, pass through.
        if (msg instanceof FullHttpRequest) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (msg instanceof HttpRequest req) {
            // PF-56: refuse messages with a decoder failure. Without this guard the aggregator
            // would happily concatenate partial body bytes from a malformed chunked stream and
            // hand the controller a fresh "successful" FullHttpRequest. Per RFC 7230 §3.4 we
            // respond with 400 Bad Request and close the connection.
            if (req.decoderResult().isFailure()) {
                Logger.warn("HttpRequest decoder failure: %s", req.decoderResult().cause());
                ReferenceCountUtil.release(msg);
                rejectBadRequest(ctx);
                return;
            }

            resetState();
            pendingRequest = req;
            // Audit M31: allocate inMemoryBody under try/release so any subsequent
            // throw on this code path doesn't leak the CompositeByteBuf. The
            // exceptionCaught handler also calls resetState (defensive belt+braces),
            // but local cleanup makes the invariant explicit.
            inMemoryBody = ctx.alloc().compositeBuffer();

            // PF-55: handle Expect: 100-continue. Strict upload clients (curl --expect100,
            // Apache HttpClient) wait for a 100 response before sending the body, and will
            // hang forever if we silently swallow the request. If a Content-Length is present
            // and exceeds the configured cap, fail fast with 413; otherwise acknowledge with
            // 100 Continue so the client streams the body.
            if (HttpUtil.is100ContinueExpected(req)) {
                long declaredLength = HttpUtil.getContentLength(req, -1L);
                if (maxContentLength >= 0 && declaredLength > maxContentLength) {
                    Logger.warn("Expect: 100-continue with declared CL %d > maxContentLength %d; rejecting", declaredLength, maxContentLength);
                    rejectOversize(ctx);
                    return;
                }
                FullHttpResponse cont = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER);
                // Write via the channel so the message traverses HttpResponseEncoder. ctx.write
                // here would skip the encoder (it's tail-ward of this handler) and ship a raw
                // FullHttpResponse object to the socket.
                ctx.channel().writeAndFlush(cont);
            }
            return;
        }

        // HttpContent (including LastHttpContent)
        HttpContent chunk = (HttpContent) msg;
        try {
            if (rejected) {
                // We already responded with 413; just drain remaining content.
                if (chunk instanceof LastHttpContent) {
                    resetState();
                }
                return;
            }

            // PF-56: a decoder failure mid-stream (e.g. malformed chunk size) must abort the
            // request rather than be swallowed silently.
            if (chunk.decoderResult().isFailure()) {
                Logger.warn("HttpContent decoder failure: %s", chunk.decoderResult().cause());
                rejectBadRequest(ctx);
                return;
            }

            ByteBuf buf = chunk.content();
            int len = buf.readableBytes();
            bytesReceived += len;

            if (maxContentLength >= 0 && bytesReceived > maxContentLength) {
                Logger.warn("Request body exceeded play.netty.maxContentLength (%d bytes); rejecting with 413", maxContentLength);
                rejectOversize(ctx);
                return;
            }

            if (!overflow && bytesReceived > spoolThresholdBytes) {
                // Switch from in-memory accumulation to disk.
                // Audit M31: createSpoolFile / FileOutputStream / readBytes can throw
                // IOException (disk full, permission denied). If they do, ensure the
                // in-memory composite buffer is released here rather than waiting for
                // exceptionCaught/channelInactive to fire and call resetState.
                try {
                    overflow = true;
                    spoolFile = createSpoolFile();
                    spoolOut = new FileOutputStream(spoolFile);
                    if (inMemoryBody != null && inMemoryBody.isReadable()) {
                        inMemoryBody.readBytes(spoolOut, inMemoryBody.readableBytes());
                    }
                } finally {
                    if (inMemoryBody != null) {
                        inMemoryBody.release();
                        inMemoryBody = null;
                    }
                }
            }

            if (overflow) {
                buf.readBytes(spoolOut, len);
            } else {
                // retain so the slice survives chunk.release() below
                inMemoryBody.addComponent(true, buf.retain());
            }

            if (chunk instanceof LastHttpContent) {
                FullHttpRequest full = buildFullRequest(ctx);
                resetState();
                ctx.fireChannelRead(full);
            }
        } finally {
            ReferenceCountUtil.release(chunk);
        }
    }

    private FullHttpRequest buildFullRequest(ChannelHandlerContext ctx) throws IOException {
        if (overflow) {
            spoolOut.flush();
            spoolOut.close();
            ctx.channel().attr(SPOOLED_BODY).set(spoolFile);
            DefaultFullHttpRequest full = new DefaultFullHttpRequest(
                    pendingRequest.protocolVersion(), pendingRequest.method(), pendingRequest.uri(),
                    Unpooled.EMPTY_BUFFER);
            full.headers().set(pendingRequest.headers());
            // PF-37: strip only the chunked token from Transfer-Encoding (the aggregated request now
            // has a known length), but preserve other content codings such as gzip/deflate so the
            // controller can still decode the body. RFC 7230 §3.3.1 lists these as comma-separated.
            stripChunkedTransferEncoding(full);
            full.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(spoolFile.length()));
            return full;
        } else {
            DefaultFullHttpRequest full = new DefaultFullHttpRequest(
                    pendingRequest.protocolVersion(), pendingRequest.method(), pendingRequest.uri(),
                    inMemoryBody);
            full.headers().set(pendingRequest.headers());
            stripChunkedTransferEncoding(full);
            full.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(inMemoryBody.readableBytes()));
            inMemoryBody = null; // ownership transferred to the FullHttpRequest
            return full;
        }
    }

    /**
     * Remove only the {@code chunked} token from any {@code Transfer-Encoding} header values.
     * Preserves coexisting codings (e.g. {@code gzip,chunked} → {@code gzip}). If only
     * {@code chunked} was present, the header is removed entirely.
     */
    private static void stripChunkedTransferEncoding(FullHttpRequest req) {
        java.util.List<String> values = req.headers().getAll(HttpHeaderNames.TRANSFER_ENCODING);
        if (values.isEmpty()) {
            return;
        }
        req.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
        for (String v : values) {
            StringBuilder kept = new StringBuilder();
            for (String token : v.split(",")) {
                String t = token.trim();
                if (t.isEmpty() || t.equalsIgnoreCase("chunked")) continue;
                if (kept.length() > 0) kept.append(", ");
                kept.append(t);
            }
            if (kept.length() > 0) {
                req.headers().add(HttpHeaderNames.TRANSFER_ENCODING, kept.toString());
            }
        }
    }

    private File createSpoolFile() throws IOException {
        File dir = (Play.tmpDir != null && Play.tmpDir.isDirectory()) ? Play.tmpDir : null;
        File f = File.createTempFile("play-upload-" + UUID.randomUUID() + "-", ".tmp", dir);
        f.deleteOnExit();
        return f;
    }

    private void rejectOversize(ChannelHandlerContext ctx) {
        rejected = true;
        // PF-44: on oversize rejection, ownership of any spool file never transfers to the channel
        // attribute (we never built a FullHttpRequest). Delete the temp file before clearing the
        // reference; cleanupSpool() deliberately does not delete because the success path transfers
        // ownership downstream.
        if (spoolFile != null) {
            File f = spoolFile;
            cleanupSpool();
            if (!f.delete()) {
                f.deleteOnExit();
            }
        } else {
            cleanupSpool();
        }
        if (inMemoryBody != null) {
            inMemoryBody.release();
            inMemoryBody = null;
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
        resp.headers().set(HttpHeaderNames.CONNECTION, "close");
        // PF-54: write through the channel (tail-most), not the handler context. Outbound from
        // this handler's ctx flows head-ward and SKIPS HttpResponseEncoder, which sits tail-ward
        // of us in the pipeline; the FullHttpResponse would reach the SocketChannel without HTTP
        // framing and the client would see a connection close instead of a valid 413. Writing via
        // ctx.channel() starts at the tail and traverses every outbound handler including the
        // encoder.
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * PF-56: respond with {@code 400 Bad Request} for messages whose decoder result is a failure
     * (malformed start-line, invalid chunk size, header line too long if a downstream limit is
     * configured, etc.) and close the connection. Same channel/encoder reasoning as
     * {@link #rejectOversize}.
     */
    private void rejectBadRequest(ChannelHandlerContext ctx) {
        rejected = true;
        if (spoolFile != null) {
            File f = spoolFile;
            cleanupSpool();
            if (!f.delete()) {
                f.deleteOnExit();
            }
        } else {
            cleanupSpool();
        }
        if (inMemoryBody != null) {
            inMemoryBody.release();
            inMemoryBody = null;
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.EMPTY_BUFFER);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
        resp.headers().set(HttpHeaderNames.CONNECTION, "close");
        ctx.channel().writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    private void resetState() {
        pendingRequest = null;
        if (inMemoryBody != null) {
            inMemoryBody.release();
            inMemoryBody = null;
        }
        cleanupSpool();
        bytesReceived = 0;
        overflow = false;
        rejected = false;
    }

    private void cleanupSpool() {
        if (spoolOut != null) {
            try { spoolOut.close(); } catch (IOException ignored) {}
            spoolOut = null;
        }
        // Note: do NOT delete spoolFile here — at this point ownership has transferred to the
        // channel attribute / PlayHandler.NettyInvocation, which is responsible for deletion.
        spoolFile = null;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // If the connection drops mid-upload, drop the partial spool to avoid a temp-file leak.
        if (spoolFile != null) {
            File f = spoolFile;
            cleanupSpool();
            if (!f.delete()) {
                f.deleteOnExit();
            }
        }
        resetState();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (spoolFile != null) {
            File f = spoolFile;
            cleanupSpool();
            if (!f.delete()) {
                f.deleteOnExit();
            }
        }
        resetState();
        super.exceptionCaught(ctx, cause);
    }
}
