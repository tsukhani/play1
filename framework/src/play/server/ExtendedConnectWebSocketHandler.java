package play.server;

import java.util.Map;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import play.Logger;
import play.mvc.Http;
import play.mvc.Router;

/**
 * PF-156: shared bootstrap for WebSockets established over an HTTP/2 (RFC 8441) or HTTP/3
 * (RFC 9220) request stream via Extended CONNECT. Subclasses supply the protocol-specific
 * frame types; everything from route resolution down is common.
 *
 * <h3>Why none of Netty's WebSocket handshake machinery applies</h3>
 *
 * RFC 8441 §5.1 deliberately drops the {@code Sec-WebSocket-Key}/{@code Sec-WebSocket-Accept}
 * exchange: h2's stream framing already provides the intermediary-confusion protection that the
 * magic-GUID hash was invented for in RFC 6455. {@code WebSocketServerHandshaker} exists solely
 * to compute that hash and rewrite an HTTP/1.1 pipeline, so it has no role here. Only the frame
 * <em>codec</em> ({@link WebSocket13FrameDecoder} / {@link WebSocket13FrameEncoder}) is reused,
 * driven over the stream's DATA frames by the bridge {@link #newDataFrameBridge()} supplies.
 *
 * <h3>Why this sits at the head of the stream pipeline</h3>
 *
 * The existing h2/h3 stream chains begin with a frame-to-{@code HttpObject} codec, and that
 * conversion destroys exactly the information Extended CONNECT carries. Netty's
 * {@code HttpConversionUtil.extractPath} returns {@code :authority} — not {@code :path} — when
 * the method is CONNECT, and its header translator silently drops every pseudo-header without an
 * HTTP/1.x mapping, which includes both {@code :protocol} and {@code :path}. A request that
 * reached {@link PlayHandler} through that codec would have no path left to route on. So the
 * detection has to happen above it, on the raw frames.
 *
 * <h3>Pipeline surgery</h3>
 *
 * On a match the HTTP chain named by the constructor is removed and replaced, between this
 * handler and the tail {@link PlayHandler}, with:
 *
 * <pre>{@code  data-bridge -> ws-decoder -> ws-encoder -> ws-aggregator -> <PlayHandler> }</pre>
 *
 * Inbound, DATA frame payloads become {@code ByteBuf}s and then {@code WebSocketFrame}s, which
 * {@link PlayHandler#channelRead} already knows how to dispatch — the same code path HTTP/1.1
 * WebSockets take. Outbound travels tail-to-head, so a {@code TextWebSocketFrame} written by a
 * controller is encoded to bytes and rewrapped as a DATA frame on its way out.
 */
public abstract class ExtendedConnectWebSocketHandler extends ChannelInboundHandlerAdapter {

    /** Value of the {@code :protocol} pseudo-header that requests a WebSocket. RFC 8441 §4. */
    protected static final String WEBSOCKET_PROTOCOL = "websocket";

    private final String[] httpChainHandlerNames;

    /**
     * @param httpChainHandlerNames pipeline names of the plain-HTTP handlers to remove when the
     *        stream turns out to be a WebSocket. Passed in rather than discovered by type so the
     *        h2 and h3 initializers stay the single source of truth for their own chain shape.
     */
    protected ExtendedConnectWebSocketHandler(String... httpChainHandlerNames) {
        this.httpChainHandlerNames = httpChainHandlerNames;
    }

    /** Write the {@code :status 200} that accepts the WebSocket. RFC 8441 §5.1. */
    protected abstract void accept(ChannelHandlerContext ctx);

    /** Write a failure status and end the stream. */
    protected abstract void reject(ChannelHandlerContext ctx, HttpResponseStatus status);

    /** A codec translating inbound DATA frames to {@code ByteBuf} and outbound back again. */
    protected abstract ChannelHandler newDataFrameBridge();

    /**
     * Resolve, accept and start a WebSocket for an Extended-CONNECT request stream. Called by
     * subclasses once they have decoded the pseudo-headers.
     *
     * @param path      the {@code :path} pseudo-header — the routable URI, query string included
     * @param authority the {@code :authority} pseudo-header, used as the HTTP/1.x {@code Host}
     * @param headers   all headers; pseudo-headers are skipped when synthesizing the request
     */
    protected final void bootstrapWebSocket(ChannelHandlerContext ctx, CharSequence path,
            CharSequence authority, Iterable<Map.Entry<CharSequence, CharSequence>> headers)
            throws Exception {

        if (path == null || path.length() == 0 || authority == null) {
            // HTTP/3 rejects this upstream (Http3HeadersSink enforces an exact
            // METHOD|SCHEME|AUTHORITY|PATH|PROTOCOL mask for Extended CONNECT), but HTTP/2 has no
            // equivalent validation anywhere in Netty, so the h2 path relies on this check.
            Logger.warn("Extended CONNECT WebSocket missing :path or :authority; rejecting");
            reject(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        ChannelPipeline pipeline = ctx.pipeline();
        PlayHandler play = pipeline.get(PlayHandler.class);
        if (play == null) {
            Logger.error("No PlayHandler in the stream pipeline; cannot serve a WebSocket");
            reject(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }

        // Synthesize the HTTP/1.1-shaped request PlayHandler.parseRequest expects. EMPTY_BUFFER
        // rather than a real body: Extended CONNECT carries no request body — the DATA frames
        // that follow are WebSocket frames, not content.
        FullHttpRequest synthetic = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, path.toString(), Unpooled.EMPTY_BUFFER);
        for (Map.Entry<CharSequence, CharSequence> header : headers) {
            CharSequence name = header.getKey();
            if (name.length() > 0 && name.charAt(0) != ':') {
                synthetic.headers().add(name, header.getValue());
            }
        }
        synthetic.headers().set(HttpHeaderNames.HOST, authority);

        // parseRequest is overridden by the h2/h3 subclasses to mark the request secure, so the
        // polymorphic call here is load-bearing: both protocols only ride over TLS.
        Http.Request request = play.parseRequest(ctx, synthetic);
        request.method = "WS";

        Map<String, String> route = Router.route(request.method, request.path);
        if (!route.containsKey("action")) {
            reject(ctx, HttpResponseStatus.NOT_FOUND);
            return;
        }

        int maxFrame = PlayHandler.maxWebSocketFrameSize();
        ChannelHandlerContext playCtx = pipeline.context(play);
        String playName = playCtx.name();

        for (String name : httpChainHandlerNames) {
            if (pipeline.get(name) != null) {
                pipeline.remove(name);
            }
        }
        pipeline.addBefore(playName, "ws-data-bridge", newDataFrameBridge());
        pipeline.addBefore(playName, "ws-decoder", new WebSocket13FrameDecoder(
                WebSocketDecoderConfig.newBuilder()
                        // RFC 6455 §5.1 still applies over h2/h3: client-to-server frames are
                        // masked, server-to-client frames are not. The transport changed, the
                        // framing did not.
                        .expectMaskedFrames(true)
                        .allowMaskMismatch(false)
                        // permessage-deflate is negotiated at handshake time on HTTP/1.1; there
                        // is no equivalent negotiation wired up here yet, so decline extensions.
                        .allowExtensions(false)
                        .maxFramePayloadLength(maxFrame)
                        .build()));
        pipeline.addBefore(playName, "ws-encoder", new WebSocket13FrameEncoder(false));
        pipeline.addBefore(playName, "ws-aggregator", new WebSocketFrameAggregator(maxFrame));

        // Accept before dispatching: the controller may write on its very first statement, and
        // ctx.writeAndFlush here starts from this handler (head-ward), bypassing the encoder we
        // just installed — which is precisely what a raw :status HEADERS frame needs.
        accept(ctx);

        // playCtx, not ctx: the Outbound writes through the context it is handed, and writing
        // from the head would skip the WebSocket frame encoder entirely.
        play.startWebSocketSession(playCtx, request, route, maxFrame);

        pipeline.remove(this);
    }
}
