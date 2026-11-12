package integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import play.server.Server;

/**
 * PF-205: a WebSocket action waiting in {@code Http.Inbound.nextEvent()} counted as in-flight
 * work, so every stop with a socket open waited the whole {@code play.shutdown.drain.timeout}
 * (JClaw's restarts went from about 3 s to 33 s while its Chrome extension stayed connected).
 *
 * <p>Each case starts a scratch application in a child JVM with a 20 s drain timeout, opens a
 * WebSocket to it and sends SIGTERM: an idle socket must not hold the drain, and one handling a
 * frame must hold it until the frame is done.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "a SIGTERM is what runs the shutdown hooks")
public class WebSocketShutdownDrainTest {

    private static final String SECRET = "wsdraintestsecret1234567890wsdraintestsecret1234567890wsdrainab";

    private static final String JUL_MANAGER = "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager";

    private static final OkHttpClient CLIENT = new OkHttpClient();

    @Test
    public void anIdleOpenSocketDoesNotHoldTheDrain(@TempDir File app) throws Exception {
        scratchApp(app);
        int port = freePort();
        Process server = server(app, port);
        awaitOk(port, server, console(app));
        Frames frames = new Frames();
        WebSocket socket = CLIENT.newWebSocket(new Request.Builder().url("ws://127.0.0.1:" + port + "/socket").build(),
                frames);
        try {
            socket.send("hello");
            assertThat(frames.text.poll(10, TimeUnit.SECONDS)).isEqualTo("echo:hello");

            long ms = stop(server, app);

            assertThat(ms).as("ms from SIGTERM to exit; console:%n%s", read(console(app))).isLessThan(10_000);
            assertThat(read(console(app))).doesNotContain("Shutdown drain timed out");
        } finally {
            socket.cancel();
        }
    }

    @Test
    public void aSocketHandlingAFrameHoldsTheDrainUntilItIsDone(@TempDir File app) throws Exception {
        scratchApp(app);
        int port = freePort();
        Process server = server(app, port);
        awaitOk(port, server, console(app));
        Frames frames = new Frames();
        WebSocket socket = CLIENT.newWebSocket(new Request.Builder().url("ws://127.0.0.1:" + port + "/socket").build(),
                frames);
        try {
            socket.send("work");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!read(console(app)).contains("WORK begins") && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            stop(server, app);

            assertThat(frames.text.poll(1, TimeUnit.SECONDS)).as("console:%n%s", read(console(app))).isEqualTo("done");
            assertThat(read(console(app))).contains("Shutdown drain: 1 in-flight request(s) completed")
                    .doesNotContain("Shutdown drain timed out");
        } finally {
            socket.cancel();
        }
    }

    private static final class Frames extends WebSocketListener {
        final BlockingQueue<String> text = new LinkedBlockingQueue<>();

        @Override
        public void onMessage(WebSocket ws, String t) {
            text.add(t);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            // the stop ends the connection without a close frame
        }
    }

    private static void scratchApp(File app) throws IOException {
        write(app, "conf/application.conf", "application.name=ws-drain\napplication.mode=prod\n"
                + "application.secret=${PLAY_SECRET}\nplay.shutdown.drain.timeout=20000\n");
        write(app, "conf/routes", "GET / Application.index\nWS /socket Socket.loop\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() { renderText(\"ok\"); }\n"
                + "}\n");
        write(app, "app/controllers/Socket.java", "package controllers;\n"
                + "public class Socket extends play.mvc.WebSocketController {\n"
                + "    public static void loop() throws Exception {\n"
                + "        for (play.mvc.Http.WebSocketEvent event : inbound) {\n"
                + "            if (event instanceof play.mvc.Http.TextFrame frame) {\n"
                + "                if (frame.data().equals(\"work\")) {\n"
                + "                    play.Logger.info(\"WORK begins\");\n"
                + "                    Thread.sleep(2_000);\n"
                + "                    outbound.send(\"done\");\n"
                + "                } else {\n"
                + "                    outbound.send(\"echo:\" + frame.data());\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
    }

    private static File console(File app) {
        return new File(app, "server.out");
    }

    /** play.server.Server on [app], as a Gradle task starts it from the working copy. */
    private static Process server(File app, int port) throws IOException {
        List<String> cmd = List.of(java(), "--enable-native-access=ALL-UNNAMED",
                "-DPLAY_SECRET=" + SECRET, "-Dapplication.path=" + app.getAbsolutePath(),
                JUL_MANAGER, "-Dlog4j2.shutdownHookEnabled=false",
                "-cp", System.getProperty("java.class.path"), Server.class.getName(), "--http.port=" + port);
        return new ProcessBuilder(cmd).directory(app).redirectErrorStream(true).redirectOutput(console(app)).start();
    }

    /** SIGTERM, wait for the JVM to end, and return the milliseconds that took. */
    private static long stop(Process server, File app) throws Exception {
        long sigterm = System.nanoTime();
        server.destroy();
        boolean exited = server.waitFor(120, TimeUnit.SECONDS);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sigterm);
        if (!exited) {
            server.destroyForcibly().waitFor();
        }
        assertThat(exited).as("the JVM ended on SIGTERM; console:%n%s", read(console(app))).isTrue();
        return ms;
    }

    private static void awaitOk(int port, Process server, File console) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build();
        while (System.nanoTime() < deadline) {
            assertThat(server.isAlive()).as("the JVM is running; console:%n%s", read(console)).isTrue();
            try {
                assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body())
                        .isEqualTo("ok");
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("GET / never answered on port " + port + "; console:\n" + read(console));
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String read(File file) throws IOException {
        return file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
    }

    private static void write(File app, String path, String content) throws IOException {
        Path file = app.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
