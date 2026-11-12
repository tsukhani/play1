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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import play.server.Server;

/**
 * PF-201: H2's own shutdown hook closed the embedded databases at SIGTERM while
 * {@code Play.shutdownHook} was still in {@code stop()}, so an {@code @OnApplicationStop} job that
 * wrote reopened the database it had just closed. Play's hook now closes H2's databases itself,
 * after {@code stop()} ({@code play.db.H2ShutdownOrder}).
 *
 * <p>Each case starts a scratch application on an AUTO_SERVER H2 file database in a child JVM, sends
 * SIGTERM and watches the database's lock file every millisecond: H2 removes it when it closes the
 * database and creates it again when it reopens it, and a JVM that ends with the database open
 * leaves it behind. The stop job waits before it writes, so that before PF-201 H2's hook had
 * always closed the database by then.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "a SIGTERM is what runs the shutdown hooks")
public class H2ShutdownTest {

    private static final String SECRET = "h2shutdowntestsecret1234567890h2shutdowntestsecret1234567890ab";

    private static final String JUL_MANAGER = "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager";

    private static final String WRITE = "    public void doJob() throws Exception {\n"
            + "        Thread.sleep(200);\n"
            + "        try (java.sql.Connection c = play.db.DB.getDataSource().getConnection()) {\n"
            + "            c.createStatement().executeUpdate(\"insert into farewell values (1)\");\n"
            + "            c.commit();\n"
            + "        }\n"
            + "        play.Logger.info(\"FAREWELL written\");\n"
            + "    }\n";

    private static final String HANG = "    public void doJob() {\n"
            + "        play.Logger.info(\"FAREWELL hangs\");\n"
            + "        while (true) {\n"
            + "            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { }\n"
            + "        }\n"
            + "    }\n";

    @Test
    public void aStopJobsWriteCommitsAndTheDatabaseClosesOnce(@TempDir File app) throws Exception {
        scratchApp(app, "prod", WRITE, "");
        int port = freePort();
        Process server = server(app, port);
        awaitOk(port, server, console(app));

        List<Boolean> lock = stopByPlaysHook(server, app);

        assertThat(lock).as("lock file present, then not, and never again; console:%n%s", read(console(app)))
                .containsExactly(true, false);
        assertThat(read(console(app))).contains("FAREWELL written").doesNotContain("90121")
                .doesNotContain("Database is already closed");
        assertThat(farewells(app)).isEqualTo(1);
    }

    @Test
    public void aDatabaseAnAutoServerClientHoldsIsClosedBeforeTheJvmExits(@TempDir File app) throws Exception {
        scratchApp(app, "prod", WRITE, "");
        int port = freePort();
        Process server = server(app, port);
        awaitOk(port, server, console(app));

        // from this JVM, through the application's AUTO_SERVER listener: the pool's close in
        // stop() leaves the database open for this session
        Connection client = DriverManager.getConnection(url(app), "sa", "");
        try {
            try (ResultSet rs = client.createStatement().executeQuery("select count(*) from farewell")) {
                assertThat(rs.next()).isTrue();
            }
            List<Boolean> lock = stopByPlaysHook(server, app);
            // left untouched until now: its next use would reconnect and take over the file
            assertThat(lock).as("closed, once, before the JVM exited; console:%n%s", read(console(app)))
                    .containsExactly(true, false);
        } finally {
            try {
                client.close();
            } catch (SQLException serverGone) {
                // its server went with the application
            }
        }
        assertThat(read(console(app))).doesNotContain("90121").doesNotContain("Database is already closed");
        assertThat(farewells(app)).isEqualTo(1);
    }

    @Test
    public void aStartThatFailsAfterTheDatabaseIsUpStillClosesIt(@TempDir File app) throws Exception {
        scratchApp(app, "prod", WRITE, "");
        try (ServerSocket taken = new ServerSocket(0)) {
            Process server = server(app, taken.getLocalPort());
            assertThat(server.waitFor(120, TimeUnit.SECONDS)).as("the JVM ended; console:%n%s", read(console(app))).isTrue();
            assertThat(server.exitValue()).as("console:%n%s", read(console(app))).isEqualTo(255);
        }

        String console = read(console(app));
        assertThat(console).contains("Connected to jdbc:h2:").contains("Could not bind on port");
        assertThat(lockFile(app)).as("closed before the JVM exited; console:%n%s", console).doesNotExist();
        assertThat(console).doesNotContain("90121").doesNotContain("Database is already closed");
    }

    @Test
    public void aHookThatHangsHasTheFallbackCloseTheDatabase(@TempDir File app) throws Exception {
        // the fallback's budget is play.jobs.stop.timeout + 3 s
        scratchApp(app, "prod", HANG, "play.jobs.stop.timeout=500\n");
        int port = freePort();
        Process server = server(app, port);
        try {
            awaitOk(port, server, console(app));
            long sigterm = System.nanoTime();
            server.destroy();

            long deadline = sigterm + TimeUnit.SECONDS.toNanos(30);
            while (Files.exists(lockFile(app)) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            long closedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sigterm);

            assertThat(lockFile(app)).as("closed by the fallback; console:%n%s", read(console(app))).doesNotExist();
            assertThat(server.isAlive()).as("Play's hook still hangs").isTrue();
            assertThat(closedAfter).isGreaterThanOrEqualTo(3_500);
            assertThat(read(console(app))).contains("FAREWELL hangs")
                    .contains("Play's shutdown hook is still stopping the application after 3500 ms");
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    @Test
    public void aRequestThatHoldsTheServerDrainLeavesTheStopJobItsWholeBudget(@TempDir File app) throws Exception {
        // stop()'s budget is play.jobs.stop.timeout + 3 s = 3.5 s, and the request holds the drain
        // before stop() for 5 s: a clock started at the SIGTERM closed H2 before the stop job wrote
        scratchApp(app, "prod", WRITE, "play.jobs.stop.timeout=500\n");
        int port = freePort();
        Process server = server(app, port);
        awaitOk(port, server, console(app));
        CompletableFuture<HttpResponse<String>> slow = HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/slow")).build(),
                HttpResponse.BodyHandlers.ofString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!read(console(app)).contains("SLOW begins") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        List<Boolean> lock = stopByPlaysHook(server, app);

        assertThat(slow.get(10, TimeUnit.SECONDS).body()).isEqualTo("slow");
        assertThat(lock).as("console:%n%s", read(console(app))).containsExactly(true, false);
        assertThat(read(console(app))).contains("1 in-flight request(s) completed").contains("FAREWELL written")
                .doesNotContain("90121").doesNotContain("Database is already closed");
        assertThat(farewells(app)).isEqualTo(1);
    }

    @Test
    public void aDevReloadLeavesH2AloneAndTheDatabaseIsClosedAtExit(@TempDir File app) throws Exception {
        scratchApp(app, "dev", WRITE, "");
        int port = freePort();
        Process server = server(app, port);
        awaitOk(port, server, console(app)); // DEV starts on the first request

        // a newer application.conf: the next request stops the application and starts it again.
        // Newer than the start, not than now: each start takes its own time as the conf's age.
        File conf = new File(app, "conf/application.conf");
        Thread.sleep(50);
        Files.writeString(conf.toPath(), read(conf) + "# reloaded\n");
        assertThat(get(port, "/terminated")).as("H2's terminated flag after the reload").isEqualTo("false");
        assertThat(read(console(app))).contains("Restart: ");

        List<Boolean> lock = stopByPlaysHook(server, app);

        // DEV's stop() leaves the pool open, so this is the close in Play's hook
        assertThat(lock).as("console:%n%s", read(console(app))).containsExactly(true, false);
        assertThat(read(console(app))).doesNotContain("90121").doesNotContain("Database is already closed");
        assertThat(farewells(app)).as("one write at the reload, one at the exit; console:%n%s", read(console(app)))
                .isEqualTo(2);
    }

    /** The lock file, sampled every millisecond: one entry per change, the first its state at the start. */
    private static final class LockWatch implements AutoCloseable {
        private final List<Boolean> states = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean running = true;
        private final Thread sampler;

        LockWatch(Path lock) {
            sampler = Thread.ofPlatform().daemon().start(() -> {
                Boolean last = null;
                while (running) {
                    boolean now = Files.exists(lock);
                    if (last == null || now != last) {
                        states.add(now);
                        last = now;
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            while (states.isEmpty()) {
                Thread.onSpinWait();
            }
        }

        List<Boolean> states() throws InterruptedException {
            close();
            return List.copyOf(states);
        }

        @Override
        public void close() throws InterruptedException {
            running = false;
            sampler.join();
        }
    }

    private static void scratchApp(File app, String mode, String farewell, String conf) throws IOException {
        write(app, "conf/application.conf", "application.name=h2-shutdown\napplication.mode=" + mode + "\n"
                + "application.secret=${PLAY_SECRET}\n"
                + "db.url=" + url(app) + "\ndb.driver=org.h2.Driver\ndb.user=sa\ndb.pass=\n" + conf);
        write(app, "conf/routes", "GET / Application.index\nGET /terminated Application.terminated\n"
                + "GET /slow Application.slow\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() { renderText(\"ok\"); }\n"
                + "    public static void slow() throws Exception {\n"
                + "        play.Logger.info(\"SLOW begins\");\n"
                + "        Thread.sleep(5_000);\n"
                + "        renderText(\"slow\");\n"
                + "    }\n"
                + "    public static void terminated() throws Exception {\n"
                + "        java.lang.reflect.Field f = Class.forName(\"org.h2.engine.OnExitDatabaseCloser\")"
                + ".getDeclaredField(\"terminated\");\n"
                + "        f.setAccessible(true);\n"
                + "        renderText(String.valueOf(f.getBoolean(null)));\n"
                + "    }\n"
                + "}\n");
        write(app, "app/jobs/Schema.java", "package jobs;\n"
                + "@play.jobs.OnApplicationStart\n"
                + "public class Schema extends play.jobs.Job<Void> {\n"
                + "    public void doJob() throws Exception {\n"
                + "        try (java.sql.Connection c = play.db.DB.getDataSource().getConnection()) {\n"
                + "            c.createStatement().execute(\"create table if not exists farewell (n int)\");\n"
                + "            c.commit();\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        write(app, "app/jobs/Farewell.java", "package jobs;\n"
                + "@play.jobs.OnApplicationStop\n"
                + "public class Farewell extends play.jobs.Job<Void> {\n"
                + farewell
                + "}\n");
    }

    private static String url(File app) {
        return "jdbc:h2:" + new File(app, "db/app").getAbsolutePath() + ";AUTO_SERVER=TRUE";
    }

    private static Path lockFile(File app) {
        return new File(app, "db/app.lock.db").toPath();
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

    /**
     * SIGTERM, wait for the JVM to end, and return what the lock file did meanwhile. Play's hook
     * did the closing: the fallback, 33 s away, would close the databases too, but late, and it
     * returns as soon as Play's hook is done, so it neither closed them nor delayed the exit.
     */
    private static List<Boolean> stopByPlaysHook(Process server, File app) throws Exception {
        try (LockWatch watch = new LockWatch(lockFile(app))) {
            long sigterm = System.nanoTime();
            stop(server, app);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sigterm);
            assertThat(ms).as("ms from SIGTERM to exit; console:%n%s", read(console(app))).isLessThan(15_000);
            assertThat(read(console(app))).doesNotContain("still stopping the application");
            return watch.states();
        }
    }

    /** SIGTERM, and wait for the JVM to end. */
    private static void stop(Process server, File app) throws Exception {
        server.destroy();
        boolean exited = server.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            server.destroyForcibly().waitFor();
        }
        assertThat(exited).as("the JVM ended on SIGTERM; console:%n%s", read(console(app))).isTrue();
    }

    private static int farewells(File app) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(app), "sa", "");
             ResultSet rs = c.createStatement().executeQuery("select count(*) from farewell")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static void awaitOk(int port, Process server, File console) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            assertThat(server.isAlive()).as("the JVM is running; console:%n%s", read(console)).isTrue();
            try {
                assertThat(get(port, "/")).isEqualTo("ok");
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("GET / never answered on port " + port + "; console:\n" + read(console));
    }

    private static String get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
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
