package bench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.helidon.http.HeaderNames;
import io.helidon.http.media.jackson.JacksonSupport;
import io.helidon.logging.common.LogConfig;
import io.helidon.webserver.WebServer;
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

        WebServer.builder()
                .port(port)
                .host("127.0.0.1")
                .mediaContext(media -> media.addMediaSupport(JacksonSupport.create()))
                .routing(routing -> routing
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
                        }))
                .build()
                .start();
    }
}
