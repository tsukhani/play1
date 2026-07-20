package play.server.quic;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import play.server.ExtendedConnectWebSocketHandler;

/**
 * PF-158: RFC 9220 (Bootstrapping WebSockets with HTTP/3). Head of the per-stream pipeline
 * installed by {@link Http3StreamInitializer} — the HTTP/3 twin of
 * {@code play.server.ssl.Http2ExtendedConnectHandler}, sharing its bootstrap through
 * {@link ExtendedConnectWebSocketHandler}.
 *
 * <p>The server advertises the h3 {@code SETTINGS_ENABLE_CONNECT_PROTOCOL} (0x8) from
 * {@link Http3ServerInitializer}. As on HTTP/2, Netty advertises the setting but never reads it
 * back — inbound Extended CONNECT is not gated on it.
 *
 * <p>Two things differ from the h2 handler, both in our favour. Netty's {@code Http3HeadersSink}
 * implements RFC 9220 validation directly: an Extended CONNECT must carry exactly
 * {@code :method}, {@code :scheme}, {@code :authority}, {@code :path} and {@code :protocol}, so
 * malformed requests are rejected before they reach us. And {@link Http3Headers} exposes a typed
 * {@link Http3Headers#protocol()} accessor, which {@code Http2Headers} lacks.
 *
 * <p>A WebSocket keeps a QUIC request stream open indefinitely in both directions. That is
 * legal: {@code Http3RequestStreamValidationHandler} only enforces {@code content-length}
 * consistency (absent here), and the encode-state validator accepts one final HEADERS followed
 * by unbounded DATA.
 */
public class Http3ExtendedConnectHandler extends ExtendedConnectWebSocketHandler {

    public Http3ExtendedConnectHandler() {
        super("h3-codec", "h3-aggregator", "h3-chunked-write");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http3HeadersFrame frame && isWebSocketConnect(frame.headers())) {
            try {
                Http3Headers headers = frame.headers();
                bootstrapWebSocket(ctx, headers.path(), headers.authority(), headers);
            } finally {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
        // Ordinary request stream: step aside permanently so the plain h3 path is unchanged.
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(msg);
    }

    private static boolean isWebSocketConnect(Http3Headers headers) {
        return AsciiString.contentEqualsIgnoreCase(HttpMethod.CONNECT.asciiName(), headers.method())
                && AsciiString.contentEqualsIgnoreCase(WEBSOCKET_PROTOCOL, headers.protocol());
    }

    @Override
    protected void accept(ChannelHandlerContext ctx) {
        DefaultHttp3Headers headers = new DefaultHttp3Headers();
        headers.status(HttpResponseStatus.OK.codeAsText());
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(headers));
    }

    @Override
    protected void reject(ChannelHandlerContext ctx, HttpResponseStatus status) {
        DefaultHttp3Headers headers = new DefaultHttp3Headers();
        headers.status(status.codeAsText());
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(headers))
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    protected ChannelHandler newDataFrameBridge() {
        return new Http3WebSocketDataBridge();
    }

    /**
     * Unwraps WebSocket bytes from h3 DATA frames and rewraps them on the way out.
     *
     * <p>{@link MessageToMessageCodec} releases the message it was handed once the callback
     * returns, hence the {@code retain()} in both directions.
     */
    static final class Http3WebSocketDataBridge extends MessageToMessageCodec<Http3DataFrame, ByteBuf> {

        @Override
        protected void decode(ChannelHandlerContext ctx, Http3DataFrame frame, List<Object> out) {
            if (frame.content().isReadable()) {
                out.add(frame.content().retain());
            }
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            out.add(new DefaultHttp3DataFrame(msg.retain()));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt == ChannelInputShutdownEvent.INSTANCE) {
                // Peer FIN'd its half of the QUIC stream without a WebSocket close frame. On h2
                // the equivalent signal rides on the DATA frame's END_STREAM flag; here it is a
                // pipeline event. Close so PlayHandler.channelInactive releases the controller's
                // Inbound and the virtual thread parked in nextEvent() is woken.
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }
    }
}
