package integration;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-57 functional test: verifies HTTP/3 fetches the same controller body that
 * HTTP/1.1 + HTTP/2 fetch on the existing TCP listener. Reuses the server stood
 * up by {@link IntegrationServer}.
 *
 * <p>JDK 25's {@link java.net.http.HttpClient} does not support HTTP/3, so unlike
 * PF-58's test we drive Netty's HTTP/3 client directly: UDP {@link Bootstrap} →
 * {@link Http3#newQuicClientCodecBuilder} → {@link QuicChannel#newBootstrap} →
 * {@link Http3#newRequestStream}. The PEM cert+key at conf/host.cert and
 * conf/host.key (PF-68 — JKS support dropped) is loaded server-side via
 * {@link play.server.quic.Http3SslContextFactory}; the client uses
 * {@link InsecureTrustManagerFactory} so the self-signed test cert passes
 * hostname verification.
 */
public class Http3FunctionalTest {

    // 127.0.0.1 explicitly — "localhost" can resolve to ::1 on macOS, but the
    // test app pins https.address=127.0.0.1 (IPv4). UDP needs the addresses to
    // match exactly; there's no kernel-level v4-in-v6 fallback like for TCP.
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 19443;

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void http3ClientFetchesJsonOverQuic() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
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

            Channel datagramChannel = new Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(quicCodec)
                    // Bind explicitly to 127.0.0.1:0 (not bind(0)) — bind(0) with no address
                    // can land on ::0 (IPv6 wildcard) on macOS, and UDP packets from an
                    // IPv6-bound socket don't always reach an IPv4-bound server listener.
                    .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

            QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                    // Http3ClientConnectionHandler must be on the QuicChannel pipeline; without
                    // it Http3.newRequestStream creates a stream the codec can't decode because
                    // the connection's QPACK tables and control streams aren't initialized.
                    // The handshake silently completes at the QUIC layer but no h3 frames flow.
                    .handler(new Http3ClientConnectionHandler())
                    .remoteAddress(new InetSocketAddress(HOST, PORT))
                    .connect()
                    .get(5, TimeUnit.SECONDS);

            CompletableFuture<String> bodyFuture = new CompletableFuture<>();
            CompletableFuture<Integer> statusFuture = new CompletableFuture<>();

            QuicStreamChannel streamChannel = Http3.newRequestStream(quicChannel,
                    new Http3RequestStreamInboundHandler() {
                        private final StringBuilder body = new StringBuilder();

                        @Override
                        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                            CharSequence status = frame.headers().status();
                            statusFuture.complete(status == null ? -1 : Integer.parseInt(status.toString()));
                        }

                        @Override
                        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
                            ByteBuf content = frame.content();
                            body.append(content.toString(java.nio.charset.StandardCharsets.UTF_8));
                            content.release();
                        }

                        @Override
                        protected void channelInputClosed(ChannelHandlerContext ctx) {
                            bodyFuture.complete(body.toString());
                            ctx.close();
                        }
                    }).get(5, TimeUnit.SECONDS);

            DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
            headersFrame.headers()
                    .method("GET")
                    .path("/json")
                    .scheme("https")
                    .authority(HOST + ":" + PORT);
            // shutdownOutput closes the request stream's send side so the server sees EOF
            // and starts the response. RFC 9114 §4.1: a request stream is half-closed when
            // headers are sent without a body and the sender closes its side.
            streamChannel.writeAndFlush(headersFrame)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);

            int status = statusFuture.get(5, TimeUnit.SECONDS);
            String body = bodyFuture.get(5, TimeUnit.SECONDS);

            assertEquals(200, status, "h3 GET /json must return 200, body=" + body);
            assertNotNull(body, "h3 response body must not be null");
            // PF-57 AC #4: same controller serves h3 as serves h2 / h1.1, so body shape
            // must match what other versions produce.
            assertTrue(body.contains("\"status\":\"ok\""),
                    "h3 response must contain controller's JSON body, got: " + body);
            assertTrue(body.contains("\"framework\":\"play\""),
                    "h3 response must include framework field, got: " + body);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS);
        }
    }
}
