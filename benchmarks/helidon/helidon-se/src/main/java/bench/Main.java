package bench;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.helidon.common.configurable.Resource;
import io.helidon.common.pki.Keys;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.http.media.jackson.JacksonSupport;
import io.helidon.logging.common.LogConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.sse.SseSink;
import io.helidon.http.sse.SseEvent;
import io.helidon.common.media.type.MediaTypes;

/**
 * Head-to-head benchmark app, the same four endpoints as the fork's: /plaintext, /json, /db (one random row of
 * 10,000 in in-memory H2 through HikariCP) and /sse (200 named events of a small JSON object, then the end).
 */
public final class Main {

    static final String TEXT = "Hello, World!";
    static final int ROWS = 10_000;
    static final int EVENTS = 200;
    /** The fork's default security headers (its SecurityHeadersPolicy), with the same values, on every response. */
    static final List<Header> SECURITY_HEADERS = List.of(
            HeaderValues.createCached("X-Content-Type-Options", "nosniff"),
            HeaderValues.createCached("X-Frame-Options", "DENY"),
            HeaderValues.createCached("Referrer-Policy", "strict-origin-when-cross-origin"),
            HeaderValues.createCached("X-XSS-Protection", "0"),
            HeaderValues.createCached("Content-Security-Policy", "default-src 'self'"));
    /** And the fork's HSTS, on a response over TLS. */
    static final Header HSTS = HeaderValues.createCached(HeaderNames.STRICT_TRANSPORT_SECURITY,
            "max-age=31536000; includeSubDomains");

    public static void main(String[] args) throws Exception {
        LogConfig.configureRuntime();
        int port = Integer.parseInt(System.getProperty("port", "8080"));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:bench;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(Integer.parseInt(System.getProperty("db.pool.maxSize", "30")));
        HikariDataSource db = new HikariDataSource(config);
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table world(id int primary key, randomNumber int)");
            try (PreparedStatement insert = c.prepareStatement("insert into world values (?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    insert.setInt(1, i);
                    insert.setInt(2, ThreadLocalRandom.current().nextInt(1, ROWS + 1));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }

        WebServerConfig.Builder server = WebServer.builder()
                .port(port)
                .host("127.0.0.1")
                // run.py's --helidon-writes; Helidon's own defaults (direct writes, Nagle on) when it gives none
                .writeQueueLength(Integer.getInteger("write-queue-length", 0))
                .smartAsyncWrites(Boolean.getBoolean("smart-async-writes"))
                .connectionOptions(socket -> socket.tcpNoDelay(Boolean.getBoolean("tcp-no-delay")))
                .mediaContext(media -> media.addMediaSupport(JacksonSupport.create()))
                .routing(routing -> routing
                        // the security headers the fork sends, so that the three applications send the same (PF-249)
                        .addFilter((chain, req, res) -> {
                            SECURITY_HEADERS.forEach(res::header);
                            if (req.isSecure()) {
                                res.header(HSTS);
                            }
                            chain.proceed();
                        })
                        .get("/plaintext", (req, res) -> {
                            res.header(HeaderNames.CONTENT_TYPE, "text/plain");
                            res.send(TEXT);
                        })
                        .get("/json", (req, res) -> res.send(Map.of("message", TEXT)))
                        .get("/db", (req, res) -> {
                            int id = ThreadLocalRandom.current().nextInt(1, ROWS + 1);
                            try (Connection c = db.getConnection();
                                 PreparedStatement q = c.prepareStatement("select id, randomNumber from world where id = ?")) {
                                q.setInt(1, id);
                                try (ResultSet r = q.executeQuery()) {
                                    r.next();
                                    res.send(Map.of("id", r.getInt(1), "randomNumber", r.getInt(2)));
                                }
                            }
                        })
                        .get("/sse", (req, res) -> {
                            try (SseSink sink = res.sink(SseSink.TYPE)) {
                                for (int i = 0; i < EVENTS; i++) {
                                    sink.emit(SseEvent.builder()
                                            .name("token")
                                            .data(Map.of("i", i, "content", "tok"))
                                            .mediaType(MediaTypes.APPLICATION_JSON)
                                            .build());
                                }
                            }
                        }));
        // run.py's TLS cells: the listener speaks TLS with the certificate run.py generates, HTTP/2 through ALPN
        String cert = System.getProperty("tls.cert");
        if (cert != null) {
            Keys keys = Keys.builder()
                    .pem(pem -> pem.key(Resource.create(Path.of(System.getProperty("tls.key"))))
                            .certChain(Resource.create(Path.of(cert))))
                    .build();
            server.tls(tls -> tls.privateKey(keys).privateKeyCertChain(keys));
        }
        server.build().start();
    }
}
