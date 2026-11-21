package integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PF-229: Play compiles the application with javac. What a developer sees of that in DEV is
 * unchanged from ECJ's time: a compile error renders the error page with its file and line, and
 * a changed class is reloaded. And an application that sets java.enablePreview=true uses preview
 * APIs through Play's own compile, whose classes carry the preview mark. Each case starts a
 * scratch application through the real entry point in a child JVM, on a free port, with the play
 * jar as its agent as the launchers give it, and talks to it over HTTP.
 */
public class JavacDevModeTest {

    private static final String SECRET = "javacdevmodetestsecretkey1234567890javacdevmodetestsecretkey12";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    public void aCompileErrorRendersTheErrorPageWithItsFileAndLine(@TempDir File app) throws Exception {
        scratchApp(app, "", """
            package controllers;
            public class Application extends play.mvc.Controller {
                public static void index() {
                    Strin text = "never";
                    renderText(text);
                }
            }
            """);

        try (Running play = start(app)) {
            HttpResponse<String> page = get(play, "/");
            assertThat(page.statusCode()).isEqualTo(500);
            assertThat(page.body()).contains("Compilation error")
                .contains("The file <strong>/app/controllers/Application.java</strong> could not be compiled")
                .contains("In /app/controllers/Application.java (around line 4)")
                // the characters javac's diagnostic points at, marked as ECJ's were
                .contains("<em class=\"not-italic underline font-bold\">Strin</em>");

            edit(app, "app/controllers/Application.java", "Strin text", "String text");
            assertThat(get(play, "/").body()).isEqualTo("never");
        }
    }

    @Test
    public void aChangedClassIsReloaded(@TempDir File app) throws Exception {
        scratchApp(app, "", """
            package controllers;
            public class Application extends play.mvc.Controller {
                public static void index() {
                    renderText(models.Greeting.text("one"));
                }
            }
            """);
        write(app, "app/models/Greeting.java", """
            package models;
            public class Greeting {
                public static String text(String which) { return "version " + which; }
            }
            """);

        try (Running play = start(app)) {
            assertThat(get(play, "/").body()).isEqualTo("version one");

            // a body: hot-swapped in place, the signature checksum of javac's two classes being the same
            edit(app, "app/controllers/Application.java", "\"one\"", "\"two\"");
            assertThat(get(play, "/").body()).isEqualTo("version two");
            assertThat(Files.readString(new File(app, "console.log").toPath())).doesNotContain("Restart:");

            // a class the controller calls, with a new method: the application restarts
            edit(app, "app/models/Greeting.java", "public class Greeting {",
                "public class Greeting {\n    public static String other() { return \"other\"; }");
            edit(app, "app/models/Greeting.java", "\"version \"", "\"changed \"");
            assertThat(get(play, "/").body()).isEqualTo("changed two");
        }
        assertThat(Files.readString(new File(app, "console.log").toPath())).contains("Restart:");
    }

    @Test
    public void aRestartStartsTheApplicationOnAThreadThatIsNotInterrupted(@TempDir File app) throws Exception {
        // The request that finds a changed signature restarts the application on its own thread, and
        // Invoker.stop() interrupted that thread. Play.start() went on under the interrupt: javac's next
        // read of a jar there closed the jar, and every later compile of the JVM failed.
        scratchApp(app, "", """
            package controllers;
            public class Application extends play.mvc.Controller {
                public static void index() {
                    renderText(jobs.Boot.interrupted);
                }
            }
            """);
        write(app, "app/jobs/Boot.java", """
            package jobs;
            @play.jobs.OnApplicationStart
            public class Boot extends play.jobs.Job<Void> {
                public static String interrupted = "not run";
                @Override public void doJob() { interrupted = "interrupted " + Thread.currentThread().isInterrupted(); }
            }
            """);

        try (Running play = start(app)) {
            assertThat(get(play, "/").body()).isEqualTo("interrupted false");

            edit(app, "app/controllers/Application.java", "public static void index() {",
                "public static void other() { }\n    public static void index() {");
            HttpResponse<String> page = get(play, "/");
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            assertThat(page.body()).isEqualTo("interrupted false");
        }
        assertThat(Files.readString(new File(app, "console.log").toPath())).contains("Restart:");
    }

    @Test
    public void aPreviewApiCompilesAndRunsThroughPlaysOwnCompileWithJavaEnablePreview(@TempDir File app) throws Exception {
        scratchApp(app, "java.enablePreview=true\n", """
            package controllers;
            public class Application extends play.mvc.Controller {
                public static void index() {
                    StableValue<String> value = StableValue.of();
                    renderText(value.orElseSet(() -> "a preview API"));
                }
            }
            """);

        // what every launcher adds for this key, from confJvmArgs and conf_jvm_args
        try (Running play = start(app, "--enable-preview")) {
            HttpResponse<String> page = get(play, "/");
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            assertThat(page.body()).isEqualTo("a preview API");
        }

        // play precompile writes what a bundle loads
        Process precompile = new ProcessBuilder(command(app, List.of("--enable-preview", "-Dprecompile=yes", "-Dplay.id=test"), 0))
            .redirectErrorStream(true).redirectOutput(new File(app, "precompile.log")).start();
        assertThat(precompile.waitFor(120, TimeUnit.SECONDS)).isTrue();
        File classFile = new File(app, "precompiled/java/controllers/Application.class");
        assertThat(classFile).as(Files.readString(new File(app, "precompile.log").toPath())).exists();
        assertThat(ClassFile.of().parse(Files.readAllBytes(classFile.toPath())).minorVersion())
            .isEqualTo(ClassFile.PREVIEW_MINOR_VERSION);
    }

    @Test
    public void aClassCompilesAgainstWhatTheJvmsFlagsGiveIt(@TempDir File app) throws Exception {
        // ECJ read the classes through Play's class loader, so the application compiled against what the
        // JVM's flags opened to it; javac does when they reach its options, which none of these did at first
        scratchApp(app, "", """
            package controllers;
            import java.nio.ByteBuffer;
            import jdk.incubator.vector.IntVector;
            public class Application extends play.mvc.Controller {
                public static void index() {
                    long address = ((sun.nio.ch.DirectBuffer) ByteBuffer.allocateDirect(8)).address();
                    StableValue<String> preview = StableValue.of();
                    renderText((address != 0) + " " + (IntVector.SPECIES_PREFERRED.length() > 0) + " " + preview.orElseSet(() -> "preview"));
                }
            }
            """);

        try (Running play = start(app, "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED", "--add-modules=jdk.incubator.vector",
                "--enable-preview")) {
            HttpResponse<String> page = get(play, "/");
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            assertThat(page.body()).isEqualTo("true true preview");
        }
    }

    /** A Play started in a child JVM, stopped when closed. */
    private record Running(Process process, int port) implements AutoCloseable {
        @Override
        public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    private Running start(File app, String... jvmArgs) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Process process = new ProcessBuilder(command(app, List.of(jvmArgs), port))
            .redirectErrorStream(true).redirectOutput(new File(app, "console.log")).start();
        Running running = new Running(process, port);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return running;
            } catch (IOException notYet) {
                if (!process.isAlive() || System.nanoTime() > deadline) {
                    running.close();
                    throw new AssertionError("Play did not listen on " + port + ":\n"
                        + Files.readString(new File(app, "console.log").toPath()));
                }
                Thread.sleep(100);
            }
        }
    }

    private static List<String> command(File app, List<String> jvmArgs, int port) {
        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-DPLAY_SECRET=" + SECRET,
            "-Dapplication.path=" + app.getAbsolutePath()));
        // the play jar as the JVM's agent, as every launcher gives it: what makes a hot swap possible
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("play-")).forEach(cmd::add);
        cmd.addAll(jvmArgs);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "play.server.Server", "--http.port=" + port));
        return cmd;
    }

    private HttpResponse<String> get(Running play, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + play.port() + path))
            .timeout(Duration.ofSeconds(60)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void scratchApp(File app, String conf, String controller) throws IOException {
        write(app, "conf/application.conf", "application.name=javac-dev\napplication.secret=${PLAY_SECRET}\n"
            + "application.mode=dev\n" + conf);
        write(app, "conf/routes", "GET / Application.index\n");
        write(app, "app/controllers/Application.java", controller);
    }

    private static void write(File app, String path, String content) throws IOException {
        Path file = app.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Replace text in a file, and date it a second later than it was, so that no clock granularity hides the edit. */
    private static void edit(File app, String path, String from, String to) throws IOException {
        File file = new File(app, path);
        long before = file.lastModified();
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertThat(content).contains(from);
        Files.writeString(file.toPath(), content.replace(from, to), StandardCharsets.UTF_8);
        assertThat(file.setLastModified(Math.max(before + 1000, System.currentTimeMillis()))).isTrue();
    }
}
