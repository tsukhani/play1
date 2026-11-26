package integration;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
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
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-238: the event loop hands everything queued on a stream to one socket write, and a writer
 * schedules that drain only when none is pending. This is what that must not cost: an event
 * written on an idle stream still goes out at once, and a burst written faster than the socket
 * drains arrives whole and in order, over HTTP/1.1, over TLS and HTTP/2, and over QUIC and HTTP/3,
 * also to a client that stops reading for a while.
 *
 * <p>The idle client is a plain socket on the HTTP port, in the JVM that runs the server, so the
 * action's {@code System.nanoTime()} before each write and the client's at arrival are one clock.
 * It prints {@code RESULT,sse-idle,...} with the figures, for comparing builds.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SseWriteLatencyTest {

    private static final int HTTP_PORT = 19080;
    private static final Pattern IDLE_EVENT = Pattern.compile("^data: (\\d+) (\\d+)$");
    private static final Pattern BURST_EVENT = Pattern.compile("^data: \\{\"i\":(\\d+)}$");

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    /**
     * Each measured event is written after 15 ms in which nothing was. An event held back until a
     * later write, or a timer, would take about the gap or never come; the bound is half the gap.
     *
     * <p>The figure is that of the steady state, so the case runs first and the 10,000 events
     * before the measured ones, 1 ms apart, are idle writes too: they run what each measured one
     * runs (the resumeTransfer task, the wakeup of the event loop, the read of a lone chunk) until
     * C2 has compiled it, at about 5,000 calls. Otherwise the figure is that of whatever the JVM ran
     * before. A burst that took one task per event, as one did before PF-238, warmed that path; one
     * that takes a few does not, and with the bursts first and 5,000 events of warm-up the same
     * code read as up to 110 µs slower after PF-238 than before it.
     */
    @Test
    @Order(1)
    void anEventWrittenOnAnIdleStreamArrivesAtOnce() throws Exception {
        int warmUp = 10_000;
        int count = 200;
        long gapMs = 15;
        List<Long> micros = new ArrayList<>();
        stream("/sseIdle?warmUp=" + warmUp + "&count=" + count + "&gapMs=" + gapMs, warmUp + count, line -> {
            Matcher m = IDLE_EVENT.matcher(line);
            if (m.matches()) {
                long arrived = System.nanoTime();
                assertThat(Integer.parseInt(m.group(1))).isEqualTo(micros.size());
                micros.add((arrived - Long.parseLong(m.group(2))) / 1000);
            }
        });

        assertThat(micros).hasSize(warmUp + count);
        List<Long> measured = new ArrayList<>(micros.subList(warmUp, warmUp + count));
        Collections.sort(measured);
        long median = measured.get(measured.size() / 2);
        long p90 = measured.get((int) Math.ceil(measured.size() * 0.9) - 1);
        long max = measured.get(measured.size() - 1);
        System.out.printf("RESULT,sse-idle,events=%d,median_us=%d,p90_us=%d,max_us=%d%n",
                measured.size(), median, p90, max);
        assertThat(median).as("median write-to-arrival, µs").isLessThan(gapMs * 1000 / 2);
    }

    @Test
    void aBurstWrittenFasterThanTheSocketDrainsArrivesWholeAndInOrder() throws Exception {
        int count = 20_000;
        int[] next = {0};
        int chunks = stream("/sseBurst?count=" + count, count, line -> {
            if (line.startsWith("data: ")) {
                Matcher m = BURST_EVENT.matcher(line);
                assertThat(m.matches()).as("intact event: %s", line).isTrue();
                assertThat(Integer.parseInt(m.group(1))).isEqualTo(next[0]++);
            }
        });

        assertThat(next[0]).isEqualTo(count);
        System.out.printf("RESULT,sse-burst,events=%d,http_chunks=%d%n", count, chunks);
    }

    /** The same burst over TLS and HTTP/2, where a drained buffer becomes DATA frames. */
    @Test
    void aBurstOverHttp2ArrivesWholeAndInOrder() throws Exception {
        int count = 20_000;
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(tls)
                .connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:19443/sseBurst?count=" + count)).build(),
                HttpResponse.BodyHandlers.ofLines());

        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
        try (Stream<String> lines = response.body()) {
            int[] next = {0};
            lines.filter(line -> line.startsWith("data: ")).forEach(line -> {
                Matcher m = BURST_EVENT.matcher(line);
                assertThat(m.matches()).as("intact event: %s", line).isTrue();
                assertThat(Integer.parseInt(m.group(1))).isEqualTo(next[0]++);
            });
            assertThat(next[0]).isEqualTo(count);
        }
    }

    /**
     * A client that stops reading. The channel goes unwritable while a resume is pending, so the
     * writes that come in meanwhile ask for none, the queue passes PF-134's high watermark (about
     * 14 MB are written) and the writer parks. Only Netty's writability event, once the client
     * reads again, resumes the transfer, and the reads that follow, 16 KiB at a time, have to
     * release the parked writer. The whole stream must arrive, in order.
     */
    @Test
    void aBurstToAClientThatStopsReadingArrivesWholeOnceItReadsAgain() throws Exception {
        int count = 500_000;
        int[] next = {0};
        stream("/sseBurst?count=" + count, count, 2_000, line -> {
            if (line.startsWith("data: ")) {
                Matcher m = BURST_EVENT.matcher(line);
                assertThat(m.matches()).as("intact event: %s", line).isTrue();
                assertThat(Integer.parseInt(m.group(1))).isEqualTo(next[0]++);
            }
        });

        assertThat(next[0]).isEqualTo(count);
    }

    /**
     * The burst over QUIC and HTTP/3, with the client holding its reads for a second first. The
     * stream may carry 1 MB before the client's flow control lets it send more, and the burst is
     * about 1.7 MB, so the server's stream channel goes unwritable with a resume pending, and only
     * the window update QUIC sends once the client reads resumes the transfer.
     */
    @Test
    void aBurstOverHttp3ArrivesWholeAndInOrderAfterTheClientHoldsItsReads() throws Exception {
        int count = 60_000;
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Channel datagram = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(Http3.newQuicClientCodecBuilder()
                            .sslContext(QuicSslContextBuilder.forClient()
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .applicationProtocols(Http3.supportedApplicationProtocols())
                                    .build())
                            .maxIdleTimeout(10_000, TimeUnit.MILLISECONDS)
                            .initialMaxData(10_000_000)
                            .initialMaxStreamDataBidirectionalLocal(1_000_000)
                            .build())
                    // 127.0.0.1 on both ends, as in Http3FunctionalTest: UDP has no v4-in-v6 fallback.
                    .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
            QuicChannel quic = QuicChannel.newBootstrap(datagram)
                    .handler(new Http3ClientConnectionHandler())
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 19443))
                    .connect().get(5, TimeUnit.SECONDS);

            CompletableFuture<Integer> status = new CompletableFuture<>();
            CompletableFuture<String> body = new CompletableFuture<>();
            QuicStreamChannel stream = Http3.newRequestStream(quic, new Http3RequestStreamInboundHandler() {
                private final ByteArrayOutputStream received = new ByteArrayOutputStream();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                    CharSequence s = frame.headers().status();
                    status.complete(s == null ? -1 : Integer.parseInt(s.toString()));
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
                    try {
                        frame.content().readBytes(received, frame.content().readableBytes());
                    } catch (IOException e) {
                        body.completeExceptionally(e);
                    } finally {
                        frame.release();
                    }
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    body.complete(received.toString(StandardCharsets.UTF_8));
                    ctx.close();
                }
            }).get(5, TimeUnit.SECONDS);
            stream.config().setAutoRead(false);

            DefaultHttp3HeadersFrame request = new DefaultHttp3HeadersFrame();
            request.headers().method("GET").path("/sseBurst?count=" + count).scheme("https")
                    .authority("127.0.0.1:19443");
            stream.writeAndFlush(request).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            Thread.sleep(1_000);
            stream.config().setAutoRead(true);

            assertThat(status.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            int[] next = {0};
            for (String line : body.get(30, TimeUnit.SECONDS).split("\n")) {
                if (line.startsWith("data: ")) {
                    Matcher m = BURST_EVENT.matcher(line);
                    assertThat(m.matches()).as("intact event: %s", line).isTrue();
                    assertThat(Integer.parseInt(m.group(1))).isEqualTo(next[0]++);
                }
            }
            assertThat(next[0]).isEqualTo(count);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS);
        }
    }

    private static int stream(String path, int events, Consumer<String> onLine) throws IOException {
        return stream(path, events, 0, onLine);
    }

    /**
     * GETs {@code path}, reads nothing more for {@code stallMs} after the headers, and hands each
     * line of the de-chunked body to {@code onLine} until {@code events} {@code data:} lines have
     * come, or the terminating chunk. Returns the number of HTTP chunks the body came in.
     */
    private static int stream(String path, int events, long stallMs, Consumer<String> onLine) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", HTTP_PORT)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ChunkedReader in = new ChunkedReader(socket.getInputStream());
            String status = in.rawLine();
            assertThat(status).startsWith("HTTP/1.1 200");
            boolean chunked = false;
            for (String h; !(h = in.rawLine()).isEmpty(); ) {
                chunked |= h.toLowerCase().replace(" ", "").equals("transfer-encoding:chunked");
            }
            assertThat(chunked).as("Transfer-Encoding: chunked").isTrue();
            if (stallMs > 0) {
                try {
                    Thread.sleep(stallMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            int seen = 0;
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (seen < events) {
                int size = Integer.parseInt(in.rawLine().trim(), 16);
                if (size == 0) {
                    break;
                }
                in.chunks++;
                for (int i = 0; i < size; i++) {
                    int b = in.read();
                    if (b == '\n') {
                        String text = line.toString(StandardCharsets.UTF_8);
                        if (text.startsWith("data: ")) {
                            seen++;
                        }
                        onLine.accept(text);
                        line.reset();
                    } else {
                        line.write(b);
                    }
                }
                assertThat(in.rawLine()).as("CRLF after a chunk").isEmpty();
            }
            return in.chunks;
        }
    }

    /** Reads byte by byte from a buffer of its own, so a read returns as soon as bytes arrive. */
    private static final class ChunkedReader {
        private final InputStream in;
        private final byte[] buf = new byte[65536];
        private int pos;
        private int end;
        int chunks;

        ChunkedReader(InputStream in) {
            this.in = in;
        }

        int read() throws IOException {
            if (pos == end) {
                end = in.read(buf);
                pos = 0;
                if (end <= 0) {
                    throw new IOException("the stream ended early");
                }
            }
            return buf[pos++] & 0xff;
        }

        /** A line ending in CRLF, without it. */
        String rawLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int b; (b = read()) != '\n'; ) {
                if (b != '\r') {
                    sb.append((char) b);
                }
            }
            return sb.toString();
        }
    }
}
