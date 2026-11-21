package controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import play.db.DB;
import play.mvc.Controller;
import play.mvc.SseStream;

/**
 * Head-to-head benchmark actions, the same four endpoints as the Helidon app's: /plaintext, /json, /db (one random
 * row of 10,000 in in-memory H2 through HikariCP) and /sse (200 named events of a small JSON object, then the end).
 */
public class Bench extends Controller {

    static final String TEXT = "Hello, World!";
    static final int EVENTS = 200;

    public static void plaintext() {
        renderText("Hello, World!");
    }

    public static void json() {
        renderJSON(Map.of("message", TEXT));
    }

    public static void db() throws SQLException {
        int id = ThreadLocalRandom.current().nextInt(1, jobs.Seed.ROWS + 1);
        try (Connection c = DB.getDataSource().getConnection();
             PreparedStatement q = c.prepareStatement("select id, randomNumber from world where id = ?")) {
            q.setInt(1, id);
            try (ResultSet r = q.executeQuery()) {
                r.next();
                renderJSON(Map.of("id", r.getInt(1), "randomNumber", r.getInt(2)));
            }
        }
    }

    public static void jpa() {
        models.World world = models.World.findById(ThreadLocalRandom.current().nextInt(1, jobs.Seed.ROWS + 1));
        renderJSON(Map.of("id", world.id, "randomNumber", world.randomNumber));
    }

    public static void sse() {
        SseStream sse = openSSE();
        for (int i = 0; i < EVENTS; i++) {
            sse.sendEvent("token", Map.of("i", i, "content", "tok"));
        }
        sse.close();
    }
}
