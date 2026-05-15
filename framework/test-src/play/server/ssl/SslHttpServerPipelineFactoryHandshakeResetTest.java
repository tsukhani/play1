package play.server.ssl;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import play.Play;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-109: end-to-end wiring check that a {@code "Connection reset"} IOException
 * propagating up a real, fully-wired SSL pipeline is consumed by
 * {@link SslHandshakeExceptionSuppressor} before it would reach the
 * {@code DefaultChannelPipeline} tail (where the unhelpful "reached at the tail
 * of the pipeline" WARN originates).
 *
 * <p>Why this shape: the bug's natural reproduction would open a TLS socket and
 * force RST via {@code SO_LINGER 0}, but the resulting kernel behavior is
 * OS-dependent — macOS and Windows both have well-known cases where the close
 * FINs cleanly instead of producing a RST. That makes a real-RST test pass
 * regardless of whether the suppressor is wired in, which is worse than no test.
 *
 * <p>Instead we bind a real Netty server using {@link SslHttpServerPipelineFactory},
 * accept a real TCP connection (so {@code ssl} + {@code alpn} +
 * {@code handshake-exc-suppressor} all install), append a "tail-spy" handler
 * that proxies for {@code DefaultChannelPipeline}'s real tail, then fire the
 * exact {@link SocketException} the bug describes from inside the server's
 * event loop.
 *
 * <p>If the suppressor is wired in: ALPN re-fires the exception → suppressor
 * consumes & closes → tail-spy never sees it. If someone deletes the suppressor
 * line from {@link SslHttpServerPipelineFactory#initChannel}: the exception
 * propagates through ssl → alpn → tail-spy, which proves that the
 * {@code DefaultChannelPipeline} tail would have fired its WARN at the same
 * position. The pre-existing {@link SslHandshakeExceptionSuppressorTest}
 * covers the handler's branch behavior (which messages are suppressed, which
 * propagate, self-removal on handshake completion).
 */
class SslHttpServerPipelineFactoryHandshakeResetTest {

    private Properties savedConfig;
    private File savedApplicationPath;
    private Path tmpDir;

    @BeforeEach
    void setUp() throws Exception {
        savedConfig = Play.configuration;
        savedApplicationPath = Play.applicationPath;
        Play.configuration = new Properties();
        tmpDir = Files.createTempDirectory("pf109-test-");
        Play.applicationPath = tmpDir.toFile();
        resetCachedSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        Play.configuration = savedConfig;
        Play.applicationPath = savedApplicationPath;
        resetCachedSslContext();
        if (tmpDir != null) {
            File[] files = tmpDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        File[] inner = f.listFiles();
                        if (inner != null) for (File i : inner) i.delete();
                        f.delete();
                    } else {
                        f.delete();
                    }
                }
            }
            tmpDir.toFile().delete();
        }
    }

    @Test
    void connectionResetOnSslPipelineDoesNotReachDefaultPipelineTail() throws Exception {
        generatePemCertAndKey("certs/host.cert", "certs/host.key");
        Play.configuration.setProperty("certificate.file", "certs/host.cert");
        Play.configuration.setProperty("certificate.key.file", "certs/host.key");

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        AtomicReference<Channel> childChannelRef = new AtomicReference<>();
        AtomicReference<Throwable> reachedTail = new AtomicReference<>();
        CountDownLatch childActive = new CountDownLatch(1);

        try {
            SslHttpServerPipelineFactory productionFactory = new SslHttpServerPipelineFactory();
            ServerBootstrap b = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            // Capture the child channel so the test thread can fire from
                            // its event loop. Probe is at the head so it runs before
                            // anything mutates the pipeline.
                            ch.pipeline().addLast("test-probe", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    childChannelRef.set(ctx.channel());
                                    childActive.countDown();
                                    ctx.fireChannelActive();
                                }
                            });
                            // Real production initChannel — installs ssl, alpn, suppressor
                            // in exactly the same order operators see in prod. Protected
                            // method, accessible because the test lives in the same package.
                            productionFactory.initChannel(ch);
                            // Tail-spy proxies for DefaultChannelPipeline.tail: anything that
                            // would reach the real tail will reach this handler first. If the
                            // exception lands here the suppressor failed and the bug
                            // would manifest in production as the unhelpful WARN.
                            ch.pipeline().addLast("tail-spy", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    reachedTail.set(cause);
                                }
                            });
                        }
                    });
            Channel server = b.bind(0).sync().channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            try (Socket s = new Socket("localhost", port)) {
                s.setKeepAlive(false);
                assertTrue(childActive.await(2, TimeUnit.SECONDS),
                        "server child channel did not become active");
                Channel child = childChannelRef.get();
                assertNotNull(child);
                // Fire from inside the child's event loop so the pipeline traversal
                // happens on the right thread (mirroring how Netty fires real I/O exceptions).
                child.eventLoop().submit(() -> child.pipeline()
                                .fireExceptionCaught(new SocketException("Connection reset")))
                        .sync();
                // Yield to let the exceptionCaught chain land.
                Thread.sleep(150);
            }
            server.close().sync();
        } finally {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            worker.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        assertNull(reachedTail.get(),
                "PF-109: SslHandshakeExceptionSuppressor must consume the SocketException(\"Connection reset\") "
                        + "before it reaches the tail proxy. Reaching the proxy means the same exception would "
                        + "have reached DefaultChannelPipeline.tail and triggered the \"reached at the tail of "
                        + "the pipeline\" WARN.");
    }

    private void generatePemCertAndKey(String certRelative, String keyRelative) throws Exception {
        File certOut = new File(tmpDir.toFile(), certRelative);
        File keyOut = new File(tmpDir.toFile(), keyRelative);
        certOut.getParentFile().mkdirs();
        keyOut.getParentFile().mkdirs();
        ProcessBuilder pb = new ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", keyOut.getAbsolutePath(),
                "-out", certOut.getAbsolutePath(),
                "-days", "365",
                "-subj", "/CN=localhost"
        ).redirectErrorStream(true);
        Process p = pb.start();
        int rc = p.waitFor();
        if (rc != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("openssl failed (rc=" + rc + "): " + output);
        }
    }

    private static void resetCachedSslContext() throws Exception {
        java.lang.reflect.Field f = SslHttpServerPipelineFactory.class.getDeclaredField("cachedSslContext");
        f.setAccessible(true);
        f.set(null, null);
    }
}
