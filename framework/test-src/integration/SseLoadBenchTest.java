package integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PF-134 end-to-end load bench over the real Netty/TLS SSE pipeline. NOT a correctness test —
 * it prints {@code RESULT,*} lines (captured in tests-results/) for comparing builds, then
 * trivially asserts the stream is 200. <b>Gated:</b> aborts immediately unless run with
 * {@code -Dsse.bench=1}, so normal {@code ant test} never executes the load. The build forwards
 * the flag to the integration fork via a conditional jvmarg (see build.xml integration-test).
 *
 * <pre>
 *   ant integration-test -Dsse.bench=1     # run the bench
 *   ant integration-test                   # bench is skipped (Aborted)
 * </pre>
 *
 * Measures (single stream): fast-client throughput (frames/sec) and heap growth under a stalled
 * client (the bounded-queue check — pre-PF-134 grows unbounded; PF-134 caps near the watermark).
 * Self-labels baseline-vs-HEAD by reflecting on PF-134's queuedBytes field.
 */
class SseLoadBenchTest {

    private static final String BASE = "https://localhost:19443";

    @BeforeAll
    static void startServer() {
        assumeTrue(System.getProperty("sse.bench") != null,
                "PF-134 load bench: set -Dsse.bench=1 to run");
        IntegrationServer.ensureStarted();
    }

    private static String label() {
        try {
            Class.forName("play.server.PlayHandler$LazyChunkedInput").getDeclaredField("queuedBytes");
            return "head";       // PF-134 present
        } catch (ReflectiveOperationException e) {
            return "baseline";   // pre-PF-134
        }
    }

    private static HttpClient client() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .sslContext(ctx).connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static long heap(HttpClient c) throws Exception {
        HttpResponse<String> r = c.send(
                HttpRequest.newBuilder(URI.create(BASE + "/heapUsed")).build(),
                HttpResponse.BodyHandlers.ofString());
        return Long.parseLong(r.body().trim());
    }

    private static double throughput(HttpClient c, int count, int size) throws Exception {
        HttpResponse<InputStream> resp = c.send(
                HttpRequest.newBuilder(URI.create(BASE + "/benchSse?count=" + count + "&size=" + size)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        long t0 = System.nanoTime();
        long total = 0; int n; byte[] buf = new byte[65536];
        try (InputStream in = resp.body()) {
            while ((n = in.read(buf)) >= 0) total += n;
        }
        return count / ((System.nanoTime() - t0) / 1e9);
    }

    @Test
    void loadBench() throws Exception {
        String label = label();
        HttpClient c = client();

        // ---- throughput (fast client; producer never parks) ----
        throughput(c, 30_000, 256);                       // warmup
        double best = 0;
        for (int i = 0; i < 3; i++) best = Math.max(best, throughput(c, 200_000, 256));
        System.out.printf("RESULT,%s,throughput_fps,%d,%.0f%n", label, 256, best);

        // ---- memory bound (stalled client; producer outpaces it) ----
        long idle = heap(c);
        HttpResponse<InputStream> stalled = c.send(
                HttpRequest.newBuilder(URI.create(BASE + "/benchSse?count=60000&size=1024")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        InputStream sin = stalled.body();
        byte[] b = new byte[65536]; int got = 0, n;
        while (got < 128 * 1024 && (n = sin.read(b)) >= 0) got += n;  // read a little, then stall
        Thread.sleep(2500);                                          // let producer fill / park
        long underStall = heap(c);
        sin.close();                                                 // close → wakes/ends producer
        System.out.printf("RESULT,%s,heap_idle_mb,%d%n", label, idle / (1024 * 1024));
        System.out.printf("RESULT,%s,heap_stall_mb,%d%n", label, underStall / (1024 * 1024));
        System.out.printf("RESULT,%s,heap_growth_mb,%d%n", label, (underStall - idle) / (1024 * 1024));
    }
}
