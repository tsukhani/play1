package integration;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-157 functional test: a WebSocket bootstrapped over an HTTP/2 stream with Extended CONNECT
 * (RFC 8441), exchanging frames end to end through the {@code EchoSocket} controller bound at
 * {@code WS /wsEcho} — the same controller {@link WebSocketFunctionalTest} drives over RFC 6455
 * HTTP/1.1.
 *
 * <p>The client is hand-rolled for the same reason {@link Http3FunctionalTest}'s is: no Java
 * WebSocket client library implements RFC 8441. OkHttp and the JDK's {@code HttpClient} both
 * open a separate HTTP/1.1 connection for {@code wss://}. So the test drives Netty's h2 codec
 * directly — open a stream, send {@code :method=CONNECT} + {@code :protocol=websocket}, then
 * speak RFC 6455 framing over the stream's DATA frames.
 *
 * <p>Client-side masking is the mirror of the server's: RFC 6455 §5.1 survives the transport
 * change, so the client encoder masks and its decoder expects unmasked replies.
 */
public class Http2WebSocketFunctionalTest {

    private static final String HOST = "localhost";
    private static final int PORT = 19443;
    private static final int MAX_FRAME = 65536;

    private static EventLoopGroup group;
    private static SslContext sslContext;

    @BeforeAll
    static void startServer() throws Exception {
        IntegrationServer.ensureStarted();
        group = new NioEventLoopGroup(1);
        sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();
    }

    @AfterAll
    static void stopGroup() throws Exception {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS);
    }

    // ~~~~~~~~~~~ tests

    @Test
    void serverAdvertisesConnectProtocolSetting() throws Exception {
        Connection conn = new Connection();
        try {
            Http2Settings settings = conn.serverSettings.get(5, TimeUnit.SECONDS);
            // PF-157 AC #1: SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8) must be advertised, otherwise
            // a conforming client will never attempt Extended CONNECT in the first place.
            assertEquals(Boolean.TRUE, settings.connectProtocolEnabled(),
                    "server must advertise SETTINGS_ENABLE_CONNECT_PROTOCOL; got: " + settings);
        } finally {
            conn.close();
        }
    }

    @Test
    void textFramesEchoOverExtendedConnect() throws Exception {
        Connection conn = new Connection();
        try {
            Recorder rec = conn.openWebSocket("/wsEcho");
            assertEquals(200, rec.status.get(5, TimeUnit.SECONDS),
                    "Extended CONNECT must be accepted with :status 200 (RFC 8441 section 5.1)");

            conn.stream.writeAndFlush(new TextWebSocketFrame("hello"));
            assertEquals("echo:hello", rec.text.poll(5, TimeUnit.SECONDS));
            conn.stream.writeAndFlush(new TextWebSocketFrame("world"));
            assertEquals("echo:world", rec.text.poll(5, TimeUnit.SECONDS));
        } finally {
            conn.close();
        }
    }

    @Test
    void binaryFramesEchoOverExtendedConnect() throws Exception {
        Connection conn = new Connection();
        try {
            Recorder rec = conn.openWebSocket("/wsEcho");
            assertEquals(200, rec.status.get(5, TimeUnit.SECONDS));

            byte[] payload = {1, 2, 3, 4, 5};
            conn.stream.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)));
            byte[] got = rec.binary.poll(5, TimeUnit.SECONDS);
            assertNotNull(got, "expected a binary echo frame");
            assertArrayEquals(payload, got);
        } finally {
            conn.close();
        }
    }

    @Test
    void serverDisconnectSendsCloseFrame() throws Exception {
        Connection conn = new Connection();
        try {
            Recorder rec = conn.openWebSocket("/wsEcho");
            assertEquals(200, rec.status.get(5, TimeUnit.SECONDS));

            conn.stream.writeAndFlush(new TextWebSocketFrame("quit"));
            assertEquals("bye", rec.text.poll(5, TimeUnit.SECONDS));
            // There is no WebSocketServerHandshaker on this path to write the closing handshake,
            // so this asserts PlayHandler.closeWebSocket's handshaker-free branch: peers must see
            // a proper 1000 Normal Closure, not a bare RST_STREAM (which surfaces as 1006).
            Integer closeStatus = rec.closeStatus.poll(5, TimeUnit.SECONDS);
            assertNotNull(closeStatus, "server should emit a CloseWebSocketFrame after disconnect()");
            assertEquals(1000, closeStatus.intValue(), "expected 1000 Normal Closure");
        } finally {
            conn.close();
        }
    }

    @Test
    void unroutedPathIsRejectedWithoutUpgrading() throws Exception {
        Connection conn = new Connection();
        try {
            Recorder rec = conn.openWebSocket("/nope");
            assertEquals(404, rec.status.get(5, TimeUnit.SECONDS),
                    "an Extended CONNECT for a path with no WS route must be refused, not accepted");
        } finally {
            conn.close();
        }
    }

    @Test
    void plainRequestsStillWorkOnTheSameConnection() throws Exception {
        Connection conn = new Connection();
        try {
            // The Extended-CONNECT handler removes itself from any stream that is not a
            // WebSocket, so an ordinary GET multiplexed alongside must be untouched.
            Recorder rec = new Recorder();
            Http2StreamChannel plain = conn.newPlainStream(rec);
            Http2Headers headers = new DefaultHttp2Headers();
            headers.method("GET").scheme("https").authority(HOST + ":" + PORT).path("/json");
            plain.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            assertEquals(200, rec.status.get(5, TimeUnit.SECONDS));
            assertTrue(rec.rawBody.get(5, TimeUnit.SECONDS).contains("\"status\":\"ok\""),
                    "plain h2 GET must still reach the controller unchanged");
        } finally {
            conn.close();
        }
    }

    // ~~~~~~~~~~~ client plumbing

    /** One TLS+h2 connection, plus the most recently opened stream. */
    private static final class Connection {
        final CompletableFuture<Http2Settings> serverSettings = new CompletableFuture<>();
        final Channel channel;
        Http2StreamChannel stream;

        Connection() throws Exception {
            channel = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(sslContext.newHandler(ch.alloc(), HOST, PORT))
                                    .addLast(Http2FrameCodecBuilder.forClient().build())
                                    // Ahead of the multiplex handler so connection-level SETTINGS
                                    // are observed before it decides what is a stream frame.
                                    .addLast(new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                            if (msg instanceof Http2SettingsFrame settings) {
                                                serverSettings.complete(settings.settings());
                                            }
                                            ctx.fireChannelRead(msg);
                                        }
                                    })
                                    .addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                        }
                    })
                    .connect(HOST, PORT).sync().channel();
        }

        /** A stream carrying ordinary HTTP: frames reach the recorder untranslated. */
        Http2StreamChannel newPlainStream(Recorder recorder) throws Exception {
            return new Http2StreamChannelBootstrap(channel)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(recorder);
                        }
                    })
                    .open().get(5, TimeUnit.SECONDS);
        }

        Http2StreamChannel newWebSocketStream(Recorder recorder) throws Exception {
            return new Http2StreamChannelBootstrap(channel)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline()
                                    .addLast(new ClientDataBridge())
                                    // maskPayload=true: RFC 6455 requires client-to-server masking.
                                    .addLast(new WebSocket13FrameEncoder(true))
                                    .addLast(new WebSocket13FrameDecoder(WebSocketDecoderConfig.newBuilder()
                                            .expectMaskedFrames(false)
                                            .allowMaskMismatch(true)
                                            .allowExtensions(false)
                                            .maxFramePayloadLength(MAX_FRAME)
                                            .build()))
                                    .addLast(new WebSocketFrameAggregator(MAX_FRAME))
                                    .addLast(recorder);
                        }
                    })
                    .open().get(5, TimeUnit.SECONDS);
        }

        /** Send the RFC 8441 Extended CONNECT and return the recorder bound to the stream. */
        Recorder openWebSocket(String path) throws Exception {
            Recorder recorder = new Recorder();
            stream = newWebSocketStream(recorder);

            Http2Headers headers = new DefaultHttp2Headers();
            headers.method("CONNECT")
                    .scheme("https")
                    .authority(HOST + ":" + PORT)
                    .path(path);
            // Http2Headers has no typed protocol() accessor, unlike Http3Headers.
            headers.set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket");
            headers.set("sec-websocket-version", "13");

            // No endStream: the stream stays open in both directions for the socket's lifetime.
            stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers));
            return recorder;
        }

        void close() {
            channel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }

    /** Mirror of the server's bridge: DATA frame payloads in, DATA frames out. */
    private static final class ClientDataBridge extends MessageToMessageCodec<Http2DataFrame, ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, Http2DataFrame frame, List<Object> out) {
            if (frame.content().isReadable()) {
                out.add(frame.content().retain());
            }
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            out.add(new DefaultHttp2DataFrame(msg.retain()));
        }
    }

    /** Collects the response status, WebSocket frames, and any plain-HTTP body. */
    private static final class Recorder extends ChannelInboundHandlerAdapter {
        final CompletableFuture<Integer> status = new CompletableFuture<>();
        final CompletableFuture<String> rawBody = new CompletableFuture<>();
        final BlockingQueue<String> text = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> binary = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closeStatus = new LinkedBlockingQueue<>();
        private final StringBuilder body = new StringBuilder();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                switch (msg) {
                    case Http2HeadersFrame frame -> {
                        CharSequence s = frame.headers().status();
                        status.complete(s == null ? -1 : Integer.parseInt(s.toString()));
                    }
                    case TextWebSocketFrame frame -> text.add(frame.text());
                    case BinaryWebSocketFrame frame -> binary.add(ByteBufUtil.getBytes(frame.content()));
                    case CloseWebSocketFrame frame -> closeStatus.add(frame.statusCode());
                    // A plain (non-WebSocket) stream never passes through the bridge, so its
                    // body arrives as raw Http2DataFrames.
                    case Http2DataFrame frame -> {
                        body.append(frame.content().toString(java.nio.charset.StandardCharsets.UTF_8));
                        if (frame.isEndStream()) {
                            rawBody.complete(body.toString());
                        }
                    }
                    default -> { }
                }
            } finally {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }
    }
}
