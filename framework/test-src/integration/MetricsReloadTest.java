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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.server.Server;

/**
 * PF-203: a DEV reload installs a new metrics registry and closes the old one. The connection
 * pool, which {@code DBPlugin} keeps across a reload that leaves the database configuration
 * alone, kept reporting to the closed one, so {@code hikaricp_*} vanished from {@code /@metrics}
 * after the first reload, with a WARN each time: HikariCP takes a pool's tracker only once. And a
 * cache made on first use was never measured at all, reload or not.
 *
 * <p>Each case starts a scratch DEV application in a child JVM, reads {@code /@metrics}, changes
 * {@code application.conf} so that the next request stops and starts the application, and reads
 * it again.
 */
public class MetricsReloadTest {

    private static final String SECRET = "metricsreloadtestsecret1234567890metricsreloadtestsecret123456";

    @Test
    public void aReloadKeepsThePoolsMetricsAndTheirValuesKeepMoving(@TempDir File app) throws Exception {
        scratchApp(app, "db=mem\n");
        Process server = server(app);
        try {
            List<String> before = poolSeries(metrics(app));
            assertThat(before).as("console:%n%s", read(console(app))).isNotEmpty();

            reload(app, "db=mem\n# reloaded\n");

            String after = metrics(app);
            assertThat(poolSeries(after)).as("the same series; console:%n%s", read(console(app))).isEqualTo(before);
            double acquired = acquires(after);
            assertThat(get("/db")).isEqualTo("db");
            assertThat(acquires(metrics(app))).as("still counting after the reload").isGreaterThan(acquired);
            assertThat(read(console(app))).doesNotContain("can only be set one time");
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    @Test
    public void aCacheMadeOnFirstUseIsMeasuredBeforeAndAfterAReload(@TempDir File app) throws Exception {
        scratchApp(app, "db=mem\n");
        Process server = server(app);
        try {
            assertThat(metrics(app)).as("made by GET /, after the start").contains("cache_size{cache=\"demo\"");

            // the reload discards every cache: GET / makes this one again
            reload(app, "db=mem\n# reloaded\n");

            assertThat(metrics(app)).contains("cache_size{cache=\"demo\"");
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    @Test
    public void aReloadThatChangesTheDatabaseLeavesOneSetOfPoolMetrics(@TempDir File app) throws Exception {
        scratchApp(app, "db=mem\n");
        Process server = server(app);
        try {
            List<String> before = poolSeries(metrics(app));

            // a new pool: the old one's tracker removes its meters as it closes
            reload(app, "db.url=jdbc:h2:mem:other\n");

            assertThat(read(console(app))).contains("Connected to jdbc:h2:mem:other");
            assertThat(poolSeries(metrics(app))).as("console:%n%s", read(console(app))).isEqualTo(before);
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    /** The hikaricp_ series, each a name and its labels without the value, in order. */
    private static List<String> poolSeries(String metrics) {
        return metrics.lines().filter(line -> line.startsWith("hikaricp_"))
                .map(line -> line.substring(0, line.lastIndexOf(' '))).sorted().toList();
    }

    private static double acquires(String metrics) {
        String line = metrics.lines().filter(l -> l.startsWith("hikaricp_connections_acquire_seconds_count{"))
                .findFirst().orElseThrow(() -> new AssertionError("no acquire count in:\n" + metrics));
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }

    private static void scratchApp(File app, String db) throws IOException {
        write(app, "conf/application.conf", conf(db));
        write(app, "conf/routes", "GET / Application.index\nGET /db Application.db\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() {\n"
                + "        play.cache.Caches.named(\"demo\", play.cache.CacheConfig.newBuilder().recordStats(true).build())"
                + ".put(\"k\", \"v\");\n"
                + "        renderText(\"ok\");\n"
                + "    }\n"
                + "    public static void db() throws Exception {\n"
                + "        try (java.sql.Connection c = play.db.DB.getDataSource().getConnection()) {\n"
                + "            c.createStatement().execute(\"select 1\");\n"
                + "        }\n"
                + "        renderText(\"db\");\n"
                + "    }\n"
                + "}\n");
    }

    private static String conf(String db) {
        return "application.name=metrics-reload\napplication.mode=dev\napplication.secret=${PLAY_SECRET}\n" + db;
    }

    /**
     * A newer application.conf, so that the next request stops the application and starts it
     * again. Newer than the start, not than now: each start takes its own time as the conf's age.
     */
    private static void reload(File app, String db) throws Exception {
        Thread.sleep(50);
        write(app, "conf/application.conf", conf(db));
        assertThat(get("/")).isEqualTo("ok");
        assertThat(read(console(app))).contains("Restart: ");
    }

    private static String metrics(File app) throws Exception {
        return get("/@metrics");
    }

    private static int port;

    /** play.server.Server on [app] in DEV, started by its first request (GET /). */
    private static Process server(File app) throws Exception {
        port = freePort();
        List<String> cmd = List.of(java(), "--enable-native-access=ALL-UNNAMED",
                "-DPLAY_SECRET=" + SECRET, "-Dapplication.path=" + app.getAbsolutePath(),
                "-Dlog4j2.shutdownHookEnabled=false",
                "-cp", System.getProperty("java.class.path"), Server.class.getName(), "--http.port=" + port);
        Process server = new ProcessBuilder(cmd).directory(app).redirectErrorStream(true).redirectOutput(console(app)).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            assertThat(server.isAlive()).as("the JVM is running; console:%n%s", read(console(app))).isTrue();
            try {
                assertThat(get("/")).isEqualTo("ok");
                return server;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        server.destroyForcibly().waitFor();
        throw new AssertionError("GET / never answered; console:\n" + read(console(app)));
    }

    /** From the server of the running case. */
    private static String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static File console(File app) {
        return new File(app, "server.out");
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
