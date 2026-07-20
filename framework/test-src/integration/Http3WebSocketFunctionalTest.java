package integration;

import java.net.InetSocketAddress;
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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3SettingsFrame;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * PF-158 functional test: a WebSocket bootstrapped over an HTTP/3 request stream with Extended
 * CONNECT (RFC 9220), reaching the same {@code EchoSocket} controller
 * ({@code WS /wsEcho}) that {@link WebSocketFunctionalTest} drives over HTTP/1.1 and
 * {@link Http2WebSocketFunctionalTest} drives over h2.
 *
 * <p>Client construction follows {@link Http3FunctionalTest} — UDP {@link Bootstrap} →
 * {@link Http3#newQuicClientCodecBuilder} → {@link QuicChannel#newBootstrap} →
 * {@link Http3#newRequestStream} — with the WebSocket frame codec layered over the request
 * stream's DATA frames. Unlike that test the stream is never {@code SHUTDOWN_OUTPUT}: a
 * WebSocket keeps the QUIC stream open in both directions for its whole lifetime.
 */
public class Http3WebSocketFunctionalTest {

    // 127.0.0.1 explicitly: the test app pins https.address=127.0.0.1 and UDP has no
    // v4-in-v6 fallback (see Http3FunctionalTest).
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 19443;
    private static final int MAX_FRAME = 65536;

    private static EventLoopGroup group;

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
        group = new NioEventLoopGroup(1);
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
            Http3SettingsFrame settings = conn.serverSettings.get(5, TimeUnit.SECONDS);
            // PF-158 AC #1: the h3 equivalent of SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8).
            assertEquals(Boolean.TRUE, settings.settings().connectProtocolEnabled(),
                    "server must advertise the h3 connect-protocol setting; got: " + settings);
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
                    "Extended CONNECT must be accepted with :status 200 (RFC 9220)");

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

            byte[] payload = {9, 8, 7, 6};
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
                    "an Extended CONNECT for a path with no WS route must be refused");
        } finally {
            conn.close();
        }
    }

    // ~~~~~~~~~~~ client plumbing

    /** One QUIC connection plus the request stream carrying the WebSocket. */
    private static final class Connection {
        final CompletableFuture<Http3SettingsFrame> serverSettings = new CompletableFuture<>();
        final Channel datagramChannel;
        final QuicChannel quicChannel;
        QuicStreamChannel stream;

        Connection() throws Exception {
            QuicSslContext sslCtx = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(Http3.supportedApplicationProtocols())
                    .build();

            ChannelHandler quicCodec = Http3.newQuicClientCodecBuilder()
                    .sslContext(sslCtx)
                    .maxIdleTimeout(5_000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10_000_000)
                    .initialMaxStreamDataBidirectionalLocal(1_000_000)
                    .build();

            datagramChannel = new Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(quicCodec)
                    .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

            // The first constructor argument is the inbound control-stream handler, which is
            // where the peer's SETTINGS frame is delivered.
            Http3ClientConnectionHandler connectionHandler = new Http3ClientConnectionHandler(
                    new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            if (msg instanceof Http3SettingsFrame settings) {
                                serverSettings.complete(settings);
                            }
                            ctx.fireChannelRead(msg);
                        }
                    }, null, null, null, true);

            quicChannel = QuicChannel.newBootstrap(datagramChannel)
                    .handler(connectionHandler)
                    .remoteAddress(new InetSocketAddress(HOST, PORT))
                    .connect()
                    .get(5, TimeUnit.SECONDS);
        }

        /** Send the RFC 9220 Extended CONNECT and return the recorder bound to the stream. */
        Recorder openWebSocket(String path) throws Exception {
            Recorder recorder = new Recorder();
            stream = Http3.newRequestStream(quicChannel, new ChannelInitializer<QuicStreamChannel>() {
                @Override
                protected void initChannel(QuicStreamChannel ch) {
                    ch.pipeline()
                            .addLast(new ClientDataBridge())
                            // maskPayload=true: RFC 6455 client-to-server masking is unchanged
                            // by the transport.
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
            }).get(5, TimeUnit.SECONDS);

            DefaultHttp3Headers headers = new DefaultHttp3Headers();
            // Http3HeadersSink enforces an exact METHOD|SCHEME|AUTHORITY|PATH|PROTOCOL mask for
            // Extended CONNECT, so all five must be present — client-side too.
            headers.method("CONNECT")
                    .scheme("https")
                    .authority(HOST + ":" + PORT)
                    .path(path)
                    .protocol("websocket");

            // Deliberately no SHUTDOWN_OUTPUT (unlike Http3FunctionalTest): the send side stays
            // open for the life of the WebSocket.
            stream.writeAndFlush(new DefaultHttp3HeadersFrame(headers));
            return recorder;
        }

        void close() {
            quicChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            datagramChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }

    /** Mirror of the server's bridge: DATA frame payloads in, DATA frames out. */
    private static final class ClientDataBridge extends MessageToMessageCodec<Http3DataFrame, ByteBuf> {
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
    }

    /** Collects the response status and WebSocket frames. */
    private static final class Recorder extends ChannelInboundHandlerAdapter {
        final CompletableFuture<Integer> status = new CompletableFuture<>();
        final BlockingQueue<String> text = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> binary = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closeStatus = new LinkedBlockingQueue<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                switch (msg) {
                    case Http3HeadersFrame frame -> {
                        Http3Headers headers = frame.headers();
                        CharSequence s = headers.status();
                        status.complete(s == null ? -1 : Integer.parseInt(s.toString()));
                    }
                    case TextWebSocketFrame frame -> text.add(frame.text());
                    case BinaryWebSocketFrame frame -> binary.add(ByteBufUtil.getBytes(frame.content()));
                    case CloseWebSocketFrame frame -> closeStatus.add(frame.statusCode());
                    default -> { }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}
