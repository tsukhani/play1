package bench;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import org.jboss.resteasy.reactive.RestStreamElementType;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * Head-to-head benchmark resources, the same endpoints as the fork's: /plaintext, /json, /db (JDBC, one random row
 * of 10,000 in in-memory H2 through Agroal), /jpa (the same row as an entity, in a transaction) and /sse (200 named
 * events of a small JSON object, then the end). Every method has a blocking signature, so Quarkus REST runs it on
 * its worker pool, or on a virtual thread when the build sets
 * quarkus.rest.blocking.signatures.execution.mode=virtual-thread, which is @RunOnVirtualThread on every endpoint.
 */
@Path("/")
public class BenchResource {

    static final String TEXT = "Hello, World!";
    static final int ROWS = 10_000;
    static final int EVENTS = 200;

    @Inject
    DataSource ds;

    @Inject
    EntityManager em;

    /** Fills the table at startup, as the other applications do before they serve. */
    void seed(@Observes StartupEvent event) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("delete from world");
            try (PreparedStatement insert = c.prepareStatement("insert into world (id, randomNumber) values (?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    insert.setInt(1, i);
                    insert.setInt(2, ThreadLocalRandom.current().nextInt(1, ROWS + 1));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    @GET
    @Path("plaintext")
    @Produces(MediaType.TEXT_PLAIN)
    public String plaintext() {
        return TEXT;
    }

    @GET
    @Path("json")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> json() {
        return Map.of("message", TEXT);
    }

    @GET
    @Path("db")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Integer> db() throws SQLException {
        int id = ThreadLocalRandom.current().nextInt(1, ROWS + 1);
        try (Connection c = ds.getConnection();
             PreparedStatement q = c.prepareStatement("select id, randomNumber from world where id = ?")) {
            q.setInt(1, id);
            try (ResultSet r = q.executeQuery()) {
                r.next();
                return Map.of("id", r.getInt(1), "randomNumber", r.getInt(2));
            }
        }
    }

    @GET
    @Path("jpa")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Integer> jpa() {
        World world = em.find(World.class, ThreadLocalRandom.current().nextInt(1, ROWS + 1));
        return Map.of("id", world.id, "randomNumber", world.randomNumber);
    }

    @GET
    @Path("sse")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public void sse(@Context SseEventSink sink, @Context Sse sse) throws IOException {
        try (sink) {
            for (int i = 0; i < EVENTS; i++) {
                sink.send(sse.newEventBuilder()
                        .name("token")
                        .data(Map.class, Map.of("i", i, "content", "tok"))
                        .build());
            }
        }
    }
}
