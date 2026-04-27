package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
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
        this.spoolThresholdBytes = Integer.parseInt(
                Play.configuration.getProperty("play.netty.spoolThresholdBytes", String.valueOf(DEFAULT_SPOOL_THRESHOLD)));
        this.maxContentLength = Long.parseLong(
                Play.configuration.getProperty("play.netty.maxContentLength", "-1"));
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
            resetState();
            pendingRequest = req;
            inMemoryBody = ctx.alloc().compositeBuffer();
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
                overflow = true;
                spoolFile = createSpoolFile();
                spoolOut = new FileOutputStream(spoolFile);
                if (inMemoryBody != null && inMemoryBody.isReadable()) {
                    inMemoryBody.readBytes(spoolOut, inMemoryBody.readableBytes());
                }
                if (inMemoryBody != null) {
                    inMemoryBody.release();
                    inMemoryBody = null;
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
            // Strip Transfer-Encoding: chunked since the aggregated request now has a known length.
            full.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            full.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(spoolFile.length()));
            return full;
        } else {
            DefaultFullHttpRequest full = new DefaultFullHttpRequest(
                    pendingRequest.protocolVersion(), pendingRequest.method(), pendingRequest.uri(),
                    inMemoryBody);
            full.headers().set(pendingRequest.headers());
            full.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            full.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(inMemoryBody.readableBytes()));
            inMemoryBody = null; // ownership transferred to the FullHttpRequest
            return full;
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
        // Close in-flight resources so we don't leak.
        cleanupSpool();
        if (inMemoryBody != null) {
            inMemoryBody.release();
            inMemoryBody = null;
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
        resp.headers().set(HttpHeaderNames.CONNECTION, "close");
        ctx.writeAndFlush(resp).addListener(future -> ctx.close());
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
