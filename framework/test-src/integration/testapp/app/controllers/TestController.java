package controllers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import models.Note;
import play.db.jpa.JPA;
import play.db.jpa.Transactional;
import play.mvc.Controller;
import play.mvc.SseStream;
import play.libs.Codec;
import com.google.gson.JsonObject;

public class TestController extends Controller {

    public static void index() {
        render();
    }

    public static void hello(String name) {
        render(name);
    }

    public static void echo() {
        String message = params.get("message");
        renderText("echo:" + message);
    }

    public static void redirect() {
        index();
    }

    /**
     * Serves a real File through renderBinary, which sets {@code response.direct = file}
     * and so exercises PlayHandler.copyResponse's File branch (including its 304
     * fast-path) — the other static route, /public/, goes through serveStatic instead.
     * Used by {@link integration.NotModifiedKeepAliveTest}.
     */
    public static void binaryFile() {
        renderBinary(play.Play.getFile("public/test.txt"));
    }

    public static void json() {
        JsonObject obj = new JsonObject();
        obj.addProperty("status", "ok");
        obj.addProperty("framework", "play");
        renderJSON(obj.toString());
    }

    /**
     * PF-16 integration: open an SSE stream, push three events, then close.
     * Used by {@link integration.SseFunctionalTest} to verify wire framing
     * end-to-end through the real Netty server.
     */
    public static void events() {
        SseStream sse = openSSE();
        sse.send(Map.of("seq", 1, "msg", "first"));
        sse.sendEvent("milestone", Map.of("seq", 2, "msg", "named"));
        sse.sendId("evt-3");
        sse.send(Map.of("seq", 3, "msg", "third"));
        sse.close();
    }

    // PF-134 load-bench endpoint (exercised by SseLoadBenchTest under -Dsse.bench): stream
    // `count` raw frames of `size` bytes as fast as possible. A FRESH array per frame (not a
    // reused buffer) so a stalled client makes the server retain real bytes — that's what
    // exercises the bounded-vs-unbounded LazyChunkedInput queue.
    public static void benchSse() {
        String c = params.get("count");
        String s = params.get("size");
        int count = c != null ? Integer.parseInt(c) : 100000;
        int size = s != null ? Integer.parseInt(s) : 256;
        SseStream sse = openSSE();
        for (int i = 0; i < count; i++) {
            byte[] payload = new byte[size];
            java.util.Arrays.fill(payload, (byte) 'x');
            sse.sendRaw(payload);
        }
        sse.close();
    }

    // Live-set heap probe (gc first so retained queue bytes dominate the reading).
    public static void heapUsed() {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        renderText(Long.toString(rt.totalMemory() - rt.freeMemory()));
    }

    public static void boom() {
        throw new RuntimeException("integration-test 500 trigger");
    }

    /**
     * PF-108: handler that deliberately does NOT touch JPA.em(). With the lazy
     * acquisition in PF-106, withTransaction installs only a placeholder
     * JPAContext and no HikariCP connection is leased. Drives the read-write
     * (readOnly=false) branch of JPA.withTransaction — the default.
     */
    public static void ping() {
        renderText("pong");
    }

    /**
     * PF-108: same shape as {@link #ping()} but routes through the
     * {@code readOnly=true} branch of {@link JPA#withTransaction}. Coverage
     * gate from the ticket's acceptance criteria.
     */
    @Transactional(readOnly = true)
    public static void pingReadonly() {
        renderText("pong-ro");
    }

    /**
     * PF-108: handler that calls JPA.em() and forces materialization. Drives
     * the read-write branch — the EM is acquired, the transaction begins, and
     * HikariCP leases a connection for the request's lifetime.
     */
    public static void count() {
        long n = (long) JPA.em().createQuery("select count(n) from Note n").getSingleResult();
        renderText("count:" + n);
    }

    /**
     * PF-108: same shape as {@link #count()} but on the {@code readOnly=true}
     * branch. Read-only contexts skip {@code begin()} during materialization,
     * so this also exercises the alternative materialize() path.
     */
    @Transactional(readOnly = true)
    public static void countReadonly() {
        long n = (long) JPA.em().createQuery("select count(n) from Note n").getSingleResult();
        renderText("count-ro:" + n);
    }

    /**
     * PF-107 integration: echo a raw JSON request body back to the caller along
     * with the received Content-Type. Used by {@link integration.WSAsyncFunctionalTest}
     * to verify that {@code WS.url(...).body(json).post()} round-trips a body
     * through the OkHttp transport.
     */
    public static void postJson() throws IOException {
        String received = new String(request.body.readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = new JsonObject();
        obj.addProperty("contentType", request.contentType);
        obj.addProperty("received", received);
        renderJSON(obj.toString());
    }
}
