package play.server.ssl;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import play.server.ExtendedConnectWebSocketHandler;

/**
 * PF-157: RFC 8441 (Bootstrapping WebSockets with HTTP/2). Head of the per-stream pipeline
 * installed by {@link Http2StreamInitializer}; inspects the opening HEADERS frame and either
 * turns the stream into a WebSocket or removes itself and lets the ordinary HTTP chain run.
 *
 * <p>The server advertises {@code SETTINGS_ENABLE_CONNECT_PROTOCOL} from
 * {@link Http2OrHttp1Negotiator}. Note that Netty never reads that setting back — no codec in
 * {@code netty-codec-http2} gates inbound validation on it, and {@code :protocol} is accepted as
 * a pseudo-header unconditionally. The setting is purely the advertisement RFC 8441 §3 requires;
 * every correctness check below is ours.
 *
 * <p>Unlike HTTP/3 — whose {@code Http3HeadersSink} enforces an exact
 * {@code METHOD|SCHEME|AUTHORITY|PATH|PROTOCOL} pseudo-header mask for Extended CONNECT — Netty's
 * HTTP/2 codec performs no CONNECT-shape validation at all, so a malformed request arrives here
 * intact. {@link #bootstrapWebSocket} does the null-checking that h3 gets for free.
 */
public class Http2ExtendedConnectHandler extends ExtendedConnectWebSocketHandler {

    /**
     * {@code Http2Headers} has no typed {@code protocol()} accessor (its HTTP/3 counterpart
     * does), so the pseudo-header is read by name.
     */
    private static final AsciiString PROTOCOL = Http2Headers.PseudoHeaderName.PROTOCOL.value();

    public Http2ExtendedConnectHandler() {
        super("h2-codec", "h2-aggregator", "h2-chunked-write");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame frame && isWebSocketConnect(frame.headers())) {
            try {
                Http2Headers headers = frame.headers();
                bootstrapWebSocket(ctx, headers.path(), headers.authority(), headers);
            } finally {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
        // Ordinary request stream: step out of the way for the rest of its life. Removing rather
        // than passing through keeps the steady-state pipeline byte-for-byte what it was before
        // PF-157, so the plain h2 request path pays nothing for WebSocket support.
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(msg);
    }

    private static boolean isWebSocketConnect(Http2Headers headers) {
        return AsciiString.contentEqualsIgnoreCase(HttpMethod.CONNECT.asciiName(), headers.method())
                && AsciiString.contentEqualsIgnoreCase(WEBSOCKET_PROTOCOL, headers.get(PROTOCOL));
    }

    @Override
    protected void accept(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText())));
    }

    @Override
    protected void reject(ChannelHandlerContext ctx, HttpResponseStatus status) {
        // endStream=true: nothing follows a rejection, so half-close immediately and let the
        // listener close the stream sub-channel (RST_STREAM, leaving sibling streams alone).
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(
                        new DefaultHttp2Headers().status(status.codeAsText()), true))
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    protected ChannelHandler newDataFrameBridge() {
        return new Http2WebSocketDataBridge();
    }

    /**
     * Unwraps WebSocket bytes from h2 DATA frames and rewraps them on the way out.
     *
     * <p>{@link MessageToMessageCodec} releases the message it was handed once the callback
     * returns, hence the {@code retain()} on the payload that outlives it in both directions.
     */
    static final class Http2WebSocketDataBridge extends MessageToMessageCodec<Http2DataFrame, ByteBuf> {

        @Override
        protected void decode(ChannelHandlerContext ctx, Http2DataFrame frame, List<Object> out) {
            if (frame.content().isReadable()) {
                out.add(frame.content().retain());
            }
            if (frame.isEndStream()) {
                // The peer half-closed without a WebSocket close frame. Close the stream so
                // PlayHandler.channelInactive fires and releases the controller's Inbound —
                // otherwise the virtual thread blocked in nextEvent() would never be woken.
                ctx.close();
            }
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            out.add(new DefaultHttp2DataFrame(msg.retain()));
        }
    }
}
