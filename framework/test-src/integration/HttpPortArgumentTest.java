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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import play.server.Server;

/**
 * PF-224: {@code Play.extractHttpPort} copied the command line's {@code --http.port} into
 * {@code http.port} only when it was the last program argument, while {@code Server} binds the
 * first one wherever it stands. With {@code --https.port} after it, as the bundle launcher passes
 * them when given in that order, the server bound the command line's port and {@code http.port}
 * kept application.conf's.
 *
 * <p>Each case starts a scratch DEV application in a child JVM, because what is read is the JVM's
 * own command line. Its one action answers with {@code http.port} and {@code Server.httpPort}.
 * The application is then reloaded, which reads the configuration again, and asked once more.
 */
public class HttpPortArgumentTest {

    private static final String SECRET = "httpportargumenttestsecret1234567890httpportargumenttestsecret12";

    /** What application.conf says, so that a copy that did not happen shows. */
    private static final String CONF_PORT = "1";

    @ParameterizedTest
    @ValueSource(strings = {"alone", "first", "last"})
    public void httpPortIsThePortTheServerBinds(String order, @TempDir File app) throws Exception {
        int http = freePort();
        int https = freePort(http);
        List<String> args = switch (order) {
            case "alone" -> List.of("--http.port=" + http);
            case "first" -> List.of("--http.port=" + http, "--https.port=" + https);
            default -> List.of("--https.port=" + https, "--http.port=" + http);
        };
        scratchApp(app, "http.port=" + CONF_PORT + "\n");
        Process server = server(app, http, args);
        try {
            assertThat(get(http)).as("http.port and Server.httpPort; console:%n%s", read(console(app)))
                    .isEqualTo(http + " " + http);

            assertThat(reload(app, http, "http.port=" + CONF_PORT + "\n# reloaded\n"))
                    .as("after the reload").isEqualTo(http + " " + http);
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    @Test
    public void withoutTheArgumentHttpPortKeepsTheValueInApplicationConf(@TempDir File app) throws Exception {
        int http = freePort();
        scratchApp(app, "http.port=" + http + "\n");
        Process server = server(app, http, List.of());
        try {
            assertThat(get(http)).as("console:%n%s", read(console(app))).isEqualTo(http + " " + http);

            assertThat(reload(app, http, "http.port=" + http + "\n# reloaded\n"))
                    .as("after the reload").isEqualTo(http + " " + http);
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    @Test
    public void portZeroBindsAPortTheSystemPicksAndHttpPortHoldsIt(@TempDir File app) throws Exception {
        // PF-230: how the JUnit Platform runner starts the server of each test JVM, whatever the
        // configuration names, so that the JVMs of one run never share a port
        scratchApp(app, "http.port=" + CONF_PORT + "\nhttps.port=" + CONF_PORT + "\n");
        List<String> cmd = new ArrayList<>(List.of(java(), "--enable-native-access=ALL-UNNAMED",
                "-DPLAY_SECRET=" + SECRET, "-Dapplication.path=" + app.getAbsolutePath(),
                "-Dlog4j2.shutdownHookEnabled=false",
                "-cp", System.getProperty("java.class.path"), Server.class.getName(), "--http.port=0", "--https.port=0"));
        Process server = new ProcessBuilder(cmd).directory(app).redirectErrorStream(true).redirectOutput(console(app)).start();
        try {
            int http = listening(server, app, "HTTP");
            int https = listening(server, app, "HTTPS");
            assertThat(http).isNotIn(0, Integer.parseInt(CONF_PORT));
            assertThat(https).isNotIn(0, Integer.parseInt(CONF_PORT), http);

            assertThat(get(http)).as("http.port and Server.httpPort; console:%n%s", read(console(app)))
                    .isEqualTo(http + " " + http);
            try (java.net.Socket tls = new java.net.Socket("127.0.0.1", https)) {
                assertThat(tls.isConnected()).isTrue();
            }

            assertThat(reload(app, http, "http.port=" + CONF_PORT + "\nhttps.port=" + CONF_PORT + "\n# reloaded\n"))
                    .as("after the reload").isEqualTo(http + " " + http);
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    /** The port the server's console says the [protocol] listener is on. */
    private static int listening(Process server, File app, String protocol) throws Exception {
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("Listening for " + protocol + " at \\S*:(\\d+) ");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            java.util.regex.Matcher found = line.matcher(read(console(app)));
            if (found.find()) {
                return Integer.parseInt(found.group(1));
            }
            assertThat(server.isAlive()).as("the JVM is running; console:%n%s", read(console(app))).isTrue();
            Thread.sleep(100);
        }
        throw new AssertionError("no " + protocol + " listener; console:\n" + read(console(app)));
    }

    private static void scratchApp(File app, String port) throws IOException {
        write(app, "conf/application.conf", conf(port));
        write(app, "conf/routes", "GET / Application.index\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() {\n"
                + "        renderText(play.Play.configuration.getProperty(\"http.port\") + \" \" + play.server.Server.httpPort);\n"
                + "    }\n"
                + "}\n");
        // the integration fixture's self-signed pair, at the paths HTTPS reads by default
        Path certs = Path.of(System.getProperty("user.dir"), "test-src/integration/testapp/certs");
        Files.createDirectories(app.toPath().resolve("certs"));
        Files.copy(certs.resolve("host.cert"), app.toPath().resolve("certs/host.cert"));
        Files.copy(certs.resolve("host.key"), app.toPath().resolve("certs/host.key"));
    }

    private static String conf(String port) {
        return "application.name=http-port-argument\napplication.mode=dev\napplication.secret=${PLAY_SECRET}\n"
                + "http.address=127.0.0.1\nhttps.address=127.0.0.1\n" + port;
    }

    /**
     * A newer application.conf, then a request, which stops the application and starts it again
     * before it answers. Newer than the start, not than now: each start takes its own time as the
     * conf's age. Returns that answer.
     */
    private static String reload(File app, int http, String port) throws Exception {
        Thread.sleep(50);
        write(app, "conf/application.conf", conf(port));
        String answer = get(http);
        assertThat(read(console(app))).as("the application restarted").contains("Restart: ");
        return answer;
    }

    /** play.server.Server on [app] in DEV with [args], started by its first request. */
    private static Process server(File app, int http, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java(), "--enable-native-access=ALL-UNNAMED",
                "-DPLAY_SECRET=" + SECRET, "-Dapplication.path=" + app.getAbsolutePath(),
                "-Dlog4j2.shutdownHookEnabled=false",
                "-cp", System.getProperty("java.class.path"), Server.class.getName()));
        cmd.addAll(args);
        Process server = new ProcessBuilder(cmd).directory(app).redirectErrorStream(true).redirectOutput(console(app)).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            assertThat(server.isAlive()).as("the JVM is running; console:%n%s", read(console(app))).isTrue();
            try {
                get(http);
                return server;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        server.destroyForcibly().waitFor();
        throw new AssertionError("GET / never answered; console:\n" + read(console(app)));
    }

    private static String get(int port) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static File console(File app) {
        return new File(app, "server.out");
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** A free port, other than [taken]. */
    private static int freePort(int... taken) throws IOException {
        while (true) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                if (taken.length == 0 || port != taken[0]) {
                    return port;
                }
            }
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
