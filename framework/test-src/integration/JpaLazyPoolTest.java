package integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import play.db.DB;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-108 regression test for PF-106's lazy JPA EntityManager acquisition.
 *
 * <p>PF-106 changed {@link play.db.jpa.JPA#withTransaction(String, boolean, play.libs.F.Function0)}
 * to install a placeholder {@code JPAContext} per persistence unit at request entry rather than
 * eagerly opening an {@link jakarta.persistence.EntityManager}. The EM is materialized — and the
 * HikariCP connection leased — only on the first {@link play.db.jpa.JPA#em()} call inside the
 * handler. The headline acceptance criterion is invisible to the existing test suite, which has
 * no JPA-capable integration app, so this class scaffolds the minimum needed: an H2 in-memory
 * datasource (via {@code %test.db=mem} in {@code application.conf}), a trivial {@code Note}
 * entity, and two pairs of HTTP endpoints exercising the readonly/read-write branches.
 *
 * <p>The pool signal we watch is {@link HikariPoolMXBean#getActiveConnections()} — connections
 * checked out from the pool right now. {@code getTotalConnections()} (idle + active) is the metric
 * named in the ticket but is noisier: HikariCP grows the pool past {@code minimumIdle} on first
 * concurrent demand and never shrinks back during the test's lifetime, so a stale prior /count
 * run would inflate the baseline for a subsequent /ping assertion. Active connections settle to
 * zero after every request, so the post-load equality check is sharp.
 *
 * <p>The lazy code path being asserted is:
 * <ol>
 *   <li>{@code JPA.withTransaction} runs and installs an unmaterialized {@code JPAContext} (no EM).</li>
 *   <li>{@code ping()} returns without calling {@code JPA.em()} — context stays unmaterialized.</li>
 *   <li>End-of-request commit/close paths skip unmaterialized contexts — no EM was opened.</li>
 *   <li>{@code DB.closeAll()} runs but the per-thread connection map is empty.</li>
 * </ol>
 * Pre-PF-106, step 1 unconditionally acquired an EM, which leased a HikariCP connection for the
 * full request — so the {@code /ping} active-connections assertion would fail by exactly the
 * concurrency the test fans out (50). The {@code /count} assertion would pass either way because
 * an explicit {@code JPA.em()} call materializes the EM under both code paths.
 */
public class JpaLazyPoolTest {

    private static final String BASE = "https://localhost:19443";
    private static final int CONCURRENCY = 50;

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void pingNeverLeasesConnection() throws Exception {
        HikariPoolMXBean pool = pool();
        // Baseline after boot. With db.pool.minSize=1 in the testapp config Hikari warms a single
        // idle connection at startup; active is 0 because nothing has run yet.
        int baselineActive = pool.getActiveConnections();
        assertEquals(0, baselineActive,
                "no active connections expected pre-load; if non-zero a prior test in the same JVM "
                        + "didn't release. Baseline: " + baselineActive);

        // Watcher samples active connections during the load; the assertion is over the max
        // observed mid-flight rather than just post-load, because post-load every connection has
        // been returned regardless of whether it was ever leased. Pre-PF-106 the watcher would
        // see active climb to ~50; post-PF-106 it must stay at 0.
        WatchedMax watcher = WatchedMax.start(pool);
        try {
            fireConcurrent("/ping", CONCURRENCY);
        } finally {
            watcher.stop();
        }

        assertEquals(0, watcher.max(),
                "PF-106 regression: /ping must not lease any HikariCP connection — observed max "
                        + "active=" + watcher.max() + " across " + CONCURRENCY + " concurrent requests. "
                        + "Pre-PF-106 withTransaction eagerly acquired an EM, so this would be ~" + CONCURRENCY + ".");

        // /pingRo exercises the readOnly=true branch of withTransaction — same lazy behavior
        // expected because the placeholder is installed regardless of readonly.
        WatchedMax roWatcher = WatchedMax.start(pool);
        try {
            fireConcurrent("/pingRo", CONCURRENCY);
        } finally {
            roWatcher.stop();
        }
        assertEquals(0, roWatcher.max(),
                "PF-106 regression on readOnly branch: /pingRo must not lease any connection — "
                        + "observed max active=" + roWatcher.max());
    }

    /**
     * Calibrates the instrument every other assertion here leans on. {@link
     * #pingNeverLeasesConnection()} asserts the watcher saw <em>no</em> lease — which would also
     * hold if the watcher simply never worked — so prove separately that it can see a lease that
     * is definitely present. The connection is held far longer than any plausible sampling
     * interval, which is what makes this deterministic where sampling a real request is not
     * (PF-161).
     */
    @Test
    void watcherObservesAHeldConnection() throws Exception {
        HikariPoolMXBean pool = pool();

        WatchedMax probe = WatchedMax.start(pool);
        try (Connection held = ((HikariDataSource) DB.getDataSource()).getConnection()) {
            assertNotNull(held, "could not lease a connection to calibrate the watcher");
            Thread.sleep(200);
        } finally {
            probe.stop();
        }

        assertTrue(probe.max() > 0,
                "the pool watcher never observed a connection that was held open for 200ms, so it cannot "
                        + "detect leases at all — pingNeverLeasesConnection's max==0 assertion would pass "
                        + "regardless of what /ping does and is not evidence of anything.");
    }

    @Test
    void countMaterializesAndLeasesConnection() throws Exception {
        // /count calls JPA.em() and runs a query, which forces materialization on both PF-106
        // and pre-PF-106 code paths — the false-positive guard for the /ping test.
        //
        // Asserted through the response body rather than by sampling pool state. "select count(n)
        // from Note n" cannot produce a value without a JDBC connection, so "count:0" (the Note
        // table is empty) proves both materialization and a lease. Sampling was flaky on Windows
        // CI: Thread.sleep(1) yields a ~15.6ms period there, while the lease/return cycle against
        // in-memory H2 is measured in microseconds, so the watcher stepped straight over it and
        // reported max active=0 for a request that had behaved correctly (PF-161). That the
        // watcher works at all is established by watcherObservesAHeldConnection.
        fireConcurrentExpectingBody("/count", CONCURRENCY, "count:0");

        // /countRo exercises the readOnly=true materialize() path, on the same reasoning.
        fireConcurrentExpectingBody("/countRo", CONCURRENCY, "count-ro:0");
    }

    private static HikariPoolMXBean pool() {
        Object ds = DB.getDataSource();
        assertNotNull(ds, "DB.getDataSource() returned null — JPA not wired in testapp");
        assertTrue(ds instanceof HikariDataSource,
                "Expected HikariDataSource, got: " + ds.getClass().getName());
        HikariPoolMXBean mx = ((HikariDataSource) ds).getHikariPoolMXBean();
        assertNotNull(mx, "HikariPoolMXBean unavailable — pool not initialized?");
        return mx;
    }

    private static void fireConcurrent(String path, int n) throws Exception {
        HttpClient client = httpsClient();
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>(n);
        URI uri = URI.create(BASE + path);
        for (int i = 0; i < n; i++) {
            futures.add(client.sendAsync(HttpRequest.newBuilder(uri).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, java.util.concurrent.TimeUnit.SECONDS);
        for (CompletableFuture<HttpResponse<String>> f : futures) {
            assertEquals(200, f.get().statusCode(),
                    path + " returned non-200: " + f.get().statusCode() + " body=" + f.get().body());
        }
    }

    private static void fireConcurrentExpectingBody(String path, int n, String expectedBody) throws Exception {
        HttpClient client = httpsClient();
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>(n);
        URI uri = URI.create(BASE + path);
        for (int i = 0; i < n; i++) {
            futures.add(client.sendAsync(HttpRequest.newBuilder(uri).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, java.util.concurrent.TimeUnit.SECONDS);
        for (CompletableFuture<HttpResponse<String>> f : futures) {
            HttpResponse<String> r = f.get();
            assertEquals(200, r.statusCode(),
                    path + " returned non-200: " + r.statusCode() + " body=" + r.body());
            assertEquals(expectedBody, r.body(),
                    path + " body did not match expected — got: " + r.body());
        }
    }

    private static HttpClient httpsClient() throws Exception {
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        }}, null);
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(ssl)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Polls {@link HikariPoolMXBean#getActiveConnections()} from a daemon thread and tracks
     * the max observed value. The watcher is necessary because every request returns its
     * connection at end-of-request — by the time {@code fireConcurrent} returns, active is
     * back to zero, so a single post-load read would always show 0 and the /count test would
     * be a false positive.
     */
    private static final class WatchedMax {
        private final AtomicInteger max = new AtomicInteger();
        private final Thread thread;
        private volatile boolean running = true;

        private WatchedMax(HikariPoolMXBean pool) {
            this.thread = new Thread(() -> {
                while (running) {
                    int a = pool.getActiveConnections();
                    max.accumulateAndGet(a, Math::max);
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "pf108-pool-watcher");
            this.thread.setDaemon(true);
        }

        static WatchedMax start(HikariPoolMXBean pool) {
            WatchedMax w = new WatchedMax(pool);
            w.thread.start();
            return w;
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join(2000);
        }

        int max() {
            return max.get();
        }
    }
}
