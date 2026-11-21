package integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * drains arrives whole and in order, over HTTP/1.1 and over TLS and HTTP/2.
 *
 * <p>The idle client is a plain socket on the HTTP port, in the JVM that runs the server, so the
 * action's {@code System.nanoTime()} before each write and the client's at arrival are one clock.
 * It prints {@code RESULT,sse-idle,...} with the figures, for comparing builds.
 */
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
     * <p>The 5,000 events before them, 1 ms apart, are idle writes too and warm up what each one
     * runs (the resumeTransfer task, the wakeup of the event loop, the lone-chunk read) until the
     * JIT has compiled it. Without them the figure is that of whatever the JVM ran before: a burst
     * that took one task per event, as one did before PF-238, had warmed that path for it.
     */
    @Test
    void anEventWrittenOnAnIdleStreamArrivesAtOnce() throws Exception {
        int warmUp = 5_000;
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
     * GETs {@code path} and hands each line of the de-chunked body to {@code onLine} until
     * {@code events} {@code data:} lines have come, or the terminating chunk. Returns the number
     * of HTTP chunks the body came in.
     */
    private static int stream(String path, int events, Consumer<String> onLine) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", HTTP_PORT)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Accept: text/event-stream\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ChunkedReader in = new ChunkedReader(socket.getInputStream());
            String status = in.rawLine();
            assertThat(status).startsWith("HTTP/1.1 200");
            boolean chunked = false;
            for (String h; !(h = in.rawLine()).isEmpty(); ) {
                chunked |= h.toLowerCase().replace(" ", "").equals("transfer-encoding:chunked");
            }
            assertThat(chunked).as("Transfer-Encoding: chunked").isTrue();
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
