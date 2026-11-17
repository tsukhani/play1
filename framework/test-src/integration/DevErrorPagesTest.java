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
 * PF-218: in DEV mode the error pages link {@code play-tailwind.css} through a reverse route,
 * which throws when no root holds the file. Only the application skel ships it, so an
 * application created before the skel had it, or migrated from 1.12, answered every unrouted
 * path with a 500, and an action's exception with the same template error in place of its own.
 *
 * <p>The integration testapp cannot show this: it mounts docviewer, whose {@code public/} has a
 * copy, and the lookup searches module roots. Each case starts a scratch DEV application with no
 * module in a child JVM.
 */
public class DevErrorPagesTest {

    private static final String SECRET = "deverrorpagestestsecret1234567890deverrorpagestestsecret12345";

    private static final String LINK = "href=\"/public/stylesheets/play-tailwind.css\"";

    @Test
    public void withoutTheStylesheetTheDevErrorPagesRenderWithoutIt(@TempDir File app) throws Exception {
        scratchApp(app);
        assertRendersWithoutTheStylesheet(app);
    }

    /** The skel scaffolds its own error views into every new application. */
    @Test
    public void withoutTheStylesheetTheSkelsErrorViewsRenderWithoutIt(@TempDir File app) throws Exception {
        scratchApp(app);
        Path skelViews = Path.of(System.getProperty("user.dir"), "..", "resources", "application-skel", "app", "views", "errors");
        for (String view : List.of("404.html", "500.html")) {
            write(app, "app/views/errors/" + view, Files.readString(skelViews.resolve(view), StandardCharsets.UTF_8));
        }
        assertRendersWithoutTheStylesheet(app);
    }

    private static void assertRendersWithoutTheStylesheet(File app) throws Exception {
        Process server = server(app);
        try {
            HttpResponse<String> notFound = get("/nowhere");
            assertThat(notFound.statusCode()).as("body:%n%s", notFound.body()).isEqualTo(404);
            assertThat(notFound.body()).contains("These routes have been tried").doesNotContain("play-tailwind.css");

            HttpResponse<String> failed = get("/boom");
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(failed.body()).as("the DEV 500 page, not the static fallback")
                    .contains("id=\"header\"", "pf218-boom").doesNotContain("Compilation error", "play-tailwind.css");

            assertThat(read(console(app))).doesNotContain("NoRouteFoundException");
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    @Test
    public void withTheStylesheetTheDevErrorPagesLinkIt(@TempDir File app) throws Exception {
        scratchApp(app);
        write(app, "public/stylesheets/play-tailwind.css", "/* stand-in */\n");
        Process server = server(app);
        try {
            HttpResponse<String> notFound = get("/nowhere");
            assertThat(notFound.statusCode()).isEqualTo(404);
            assertThat(notFound.body()).contains(LINK, "These routes have been tried");

            HttpResponse<String> failed = get("/boom");
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(failed.body()).contains(LINK, "pf218-boom");

            assertThat(get("/public/stylesheets/play-tailwind.css").statusCode()).as("the link resolves").isEqualTo(200);
        } finally {
            server.destroyForcibly().waitFor();
        }
    }

    private static void scratchApp(File app) throws IOException {
        write(app, "conf/application.conf",
                "application.name=dev-error-pages\napplication.mode=dev\napplication.secret=${PLAY_SECRET}\n");
        write(app, "conf/routes",
                "GET / Application.index\nGET /boom Application.boom\nGET /public/ staticDir:public\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() {\n"
                + "        renderText(\"ok\");\n"
                + "    }\n"
                + "    public static void boom() {\n"
                + "        throw new IllegalStateException(\"pf218-boom\");\n"
                + "    }\n"
                + "}\n");
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
                assertThat(get("/").body()).isEqualTo("ok");
                return server;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        server.destroyForcibly().waitFor();
        throw new AssertionError("GET / never answered; console:\n" + read(console(app)));
    }

    /** From the server of the running case. */
    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
