package integration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-147 functional test for the modernized server-side WebSocket controller API
 * (sealed {@code Http.WebSocketEvent} + record-pattern {@code switch}). Drives a real
 * RFC 6455 handshake with the OkHttp 5 WebSocket client against the integration Netty
 * server's {@code EchoSocket} controller ({@code WS /wsEcho}).
 *
 * <p>This is the first server-side WebSocket test in the suite: {@code WSAsyncFunctionalTest}
 * covers the unrelated {@code play.libs.WS} OkHttp HTTP <em>client</em>. Cleartext {@code ws://}
 * on the integration HTTP port (19080) keeps the test off the TLS path the other WS-adjacent
 * tests need.
 */
public class WebSocketFunctionalTest {

    private static final String WS_URL = "ws://127.0.0.1:19080/wsEcho";
    private static OkHttpClient client;

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
        client = new OkHttpClient();
    }

    /** Collects inbound frames and signals the server-initiated close. */
    private static final class Recorder extends WebSocketListener {
        final BlockingQueue<String> text = new LinkedBlockingQueue<>();
        final BlockingQueue<ByteString> binary = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public void onMessage(WebSocket ws, String t) { text.add(t); }
        @Override public void onMessage(WebSocket ws, ByteString bytes) { binary.add(bytes); }
        @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(1000, null); }
        @Override public void onClosed(WebSocket ws, int code, String reason) { closed.countDown(); }
    }

    @Test
    void textFramesEcho() throws Exception {
        Recorder rec = new Recorder();
        WebSocket ws = client.newWebSocket(new Request.Builder().url(WS_URL).build(), rec);

        ws.send("hello");
        assertEquals("echo:hello", rec.text.poll(5, TimeUnit.SECONDS));
        ws.send("world");
        assertEquals("echo:world", rec.text.poll(5, TimeUnit.SECONDS));

        ws.close(1000, null);
    }

    @Test
    void binaryFramesEcho() throws Exception {
        Recorder rec = new Recorder();
        WebSocket ws = client.newWebSocket(new Request.Builder().url(WS_URL).build(), rec);

        byte[] payload = {1, 2, 3, 4, 5};
        ws.send(ByteString.of(payload));
        ByteString got = rec.binary.poll(5, TimeUnit.SECONDS);
        assertNotNull(got, "expected a binary echo frame");
        assertArrayEquals(payload, got.toByteArray());

        ws.close(1000, null);
    }

    @Test
    void disconnectClosesSocket() throws Exception {
        Recorder rec = new Recorder();
        WebSocket ws = client.newWebSocket(new Request.Builder().url(WS_URL).build(), rec);

        ws.send("quit");
        assertEquals("bye", rec.text.poll(5, TimeUnit.SECONDS));
        // disconnect() unwinds the action -> onSuccess -> outbound.close() emits a
        // CloseWebSocketFrame(1000), which OkHttp surfaces as onClosing/onClosed.
        assertTrue(rec.closed.await(5, TimeUnit.SECONDS),
                "server should close the socket after disconnect()");
    }
}
