package integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import play.server.Server;

/**
 * PF-189: what an application logs while it stops has to reach its log files. Every Play JVM
 * routes java.util.logging into log4j2 (PF-175), so the first JUL call starts a log4j context
 * before {@code Logger.init}. Where the classpath holds no {@code conf/} -- a bundle (PF-180),
 * a Gradle start from the AOT cache (PF-185) -- that context finds no configuration of the
 * application's and registers log4j's own JVM shutdown hook, which no later reconfiguration
 * takes back. At SIGTERM that hook stopped logging while {@code Play.shutdownHook} was still in
 * {@code onApplicationStop}. So the launchers turn log4j's hook off and Play stops logging
 * itself, after {@code stop()}.
 *
 * <p>Each case starts a precompiled scratch application in a child JVM with the classpath of
 * that launch, jars only, logs a line from an {@code @OnApplicationStop} job, sends SIGTERM and
 * reads the files: one appender writes as it goes, one buffers, and one hands its events to a
 * thread of its own. The buffering one keeps its line only if log4j is stopped once Play has
 * stopped, since nothing flushes it otherwise. The play jar is packed here from
 * {@code classes/}, as {@code ant jar} packs it, so the case runs the framework under test.
 *
 * <p>With log4j's hook off, a JVM that ends before {@code Play.start()} has registered Play's
 * hook ({@code play precompile}, a start that fails in {@code Play.init}) relies on the one
 * {@code Server.main} registers, which the last case covers.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "a SIGTERM is what runs the shutdown hooks")
public class ShutdownLoggingTest {

    private static final String VERSION = "9.9.9-TEST";

    private static final String FAREWELL = "FAREWELL from onApplicationStop";

    private static final String JUL_MANAGER = "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager";

    private static final String SECRET = "shutdownloggingtestsecret1234567890shutdownloggingtestsecret12";

    private static final Path FRAMEWORK = Path.of(System.getProperty("user.dir"));

    @Test
    public void aBundleKeepsWhatTheApplicationLogsWhileItStops(@TempDir File bundle) throws Exception {
        scratchApp(bundle);
        // the layout playBundle writes around the application: its launcher, a framework/ with
        // the play jar and the default messages, and a .classpath of jars only. The framework's
        // lib/ jars are listed where they are rather than copied (99 MB).
        String launcher = Files.readString(FRAMEWORK.resolve("gradle-plugin/src/main/resources/bundle-play.sh"));
        Path play = bundle.toPath().resolve("play");
        Files.writeString(play, launcher.replace("__FW_VERSION__", VERSION));
        assertThat(play.toFile().setExecutable(true)).isTrue();
        List<String> classpath = new ArrayList<>();
        classpath.add("framework/play-" + VERSION + ".jar");
        playJar(new File(bundle, classpath.get(0)));
        classpath.addAll(frameworkLibJars());
        Files.createDirectories(bundle.toPath().resolve("framework/resources"));
        Files.copy(FRAMEWORK.resolve("../resources/messages"), bundle.toPath().resolve("framework/resources/messages"));
        Files.writeString(bundle.toPath().resolve(".classpath"), String.join("\n", classpath) + "\n");

        int port = freePort();
        String start = launch(bundle, "start", "--http.port=" + port);
        assertThat(start).contains("is started");
        File systemOut = new File(bundle, "logs/system.out");
        awaitOk(port, null, systemOut);
        assertThat(launch(bundle, "stop")).as("stopped; console:%n%s", read(systemOut)).contains("is stopped");

        assertFarewellInEveryLog(bundle, systemOut);
    }

    @Test
    public void aGradleStartFromTheAotCacheKeepsWhatTheApplicationLogsWhileItStops(@TempDir File tmp) throws Exception {
        File app = new File(tmp, "app");
        scratchApp(app);
        // a framework distribution as playStart reads it, the play jar at framework/play-<version>.jar,
        // which is where Play takes the framework's root from; its lib/ jars are listed where they are
        Path dist = tmp.toPath().resolve("play1");
        File playJar = dist.resolve("framework/play-" + VERSION + ".jar").toFile();
        playJar(playJar);
        List<String> classpath = new ArrayList<>(List.of(playJar.getAbsolutePath()));
        classpath.addAll(frameworkLibJars());
        Files.createDirectories(dist.resolve("resources"));
        Files.copy(FRAMEWORK.resolve("../resources/messages"), dist.resolve("resources/messages"));
        File cache = new File(app, "build/play/aot/app.aot");
        Files.createDirectories(cache.getParentFile().toPath());

        // playAotGen: a start that writes the cache on its way out of a SIGTERM
        File training = new File(app, "logs/training.out");
        int trainingPort = freePort();
        Process trainingRun = gradleStart(app, playJar, classpath, trainingPort, training, "-XX:AOTCacheOutput=" + cache);
        awaitOk(trainingPort, trainingRun, training);
        stop(trainingRun, training);
        assertThat(cache).as("training console:%n%s", read(training)).isFile();
        assertThat(new File(cache + ".config")).as("the cache was assembled to the end").doesNotExist();
        // what the start under test logs, and nothing of the training run's
        for (String log : List.of("application.log", "buffered.log", "async.log")) {
            Files.delete(app.toPath().resolve("logs").resolve(log));
        }

        // playStart once the cache is there; AOTMode=on makes the JVM refuse to start without it
        File console = new File(app, "logs/system.out");
        int port = freePort();
        Process fromCache = gradleStart(app, playJar, classpath, port, console, "-XX:AOTCache=" + cache, "-XX:AOTMode=on");
        awaitOk(port, fromCache, console);
        stop(fromCache, console);

        assertFarewellInEveryLog(app, console);
    }

    @Test
    public void aJvmThatEndsBeforePlayHasItsShutdownHookStillWritesWhatTheAppendersHold(@TempDir File app) throws Exception {
        // Play.start() registers the hook that stops log4j once the application has stopped,
        // and with log4j's own hook off Server.main stops it for a JVM that ends before that.
        scratchApp(app);
        File buffered = new File(app, "logs/buffered.log");

        // play precompile returns from Play.init before any start
        workingCopyServer(app, 0, "-Dprecompile=yes");
        assertThat(read(buffered)).contains("Done.");

        // a start that fails in Play.init, past the logging setup: fatalServerErrorOccurred() exits
        write(app, "conf/application.conf", read(new File(app, "conf/application.conf")).replace("application.mode=prod",
                "application.mode=neither"));
        workingCopyServer(app, 255);
        assertThat(read(buffered)).contains("Illegal mode 'neither'");
    }

    /**
     * The JVM playStart starts for a precompiled application that has a cache (spawnPlay in
     * Play1Plugin.kt), in its order: the AOT option, the logging properties every launcher sets,
     * then the application's own flags, and a classpath without directories.
     */
    private static Process gradleStart(File app, File playJar, List<String> classpath, int port, File console,
            String... aot) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(
            java(),
            "--enable-native-access=ALL-UNNAMED",
            "-javaagent:" + playJar.getAbsolutePath(),
            "-Dfile.encoding=utf-8",
            "-Dapplication.path=" + app.getAbsolutePath(),
            "-Dplay.id=",
            "-Dplay.version=" + VERSION));
        cmd.addAll(List.of(aot));
        cmd.addAll(List.of(
            JUL_MANAGER,
            "-Dlog4j2.shutdownHookEnabled=false",
            "-Dprecompiled=true",
            "-classpath", String.join(File.pathSeparator, classpath),
            Server.class.getName(),
            "--http.port=" + port));
        Files.createDirectories(console.getParentFile().toPath());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(app).redirectErrorStream(true).redirectOutput(console);
        pb.environment().put("PLAY_SECRET", SECRET);
        return pb.start();
    }

    /** SIGTERM, as playStop and playAotGen send it, and wait for the JVM to end. */
    private static void stop(Process p, File console) throws Exception {
        p.destroy();
        boolean exited = p.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly().waitFor();
        }
        assertThat(exited).as("the JVM ended on SIGTERM; console:%n%s", read(console)).isTrue();
    }

    private static void assertFarewellInEveryLog(File app, File console) throws IOException {
        for (String log : List.of("application.log", "buffered.log", "async.log")) {
            File file = new File(app, "logs/" + log);
            assertThat(file).as("%s; console:%n%s", log, read(console)).exists();
            assertThat(read(file)).as("logs/%s; console:%n%s", log, read(console)).contains(FAREWELL);
        }
    }

    /**
     * A precompiled application whose log4j2 configuration is not a name log4j looks for on its
     * own, and which, as JClaw's does, asks for its appenders to outlive log4j's shutdown hook.
     */
    private static void scratchApp(File app) throws Exception {
        write(app, "conf/application.conf", "application.name=shutdown-logging\napplication.mode=prod\n"
                + "application.secret=${PLAY_SECRET}\napplication.log.path=/log4j2-prod.xml\n");
        write(app, "conf/log4j2-prod.xml", String.join("\n",
            "<Configuration status=\"warn\" shutdownHook=\"disable\">",
            "  <Appenders>",
            "    <File name=\"written\" fileName=\"logs/application.log\"><PatternLayout pattern=\"%p %c - %m%n\"/></File>",
            "    <File name=\"buffered\" fileName=\"logs/buffered.log\" immediateFlush=\"false\" bufferedIo=\"true\">",
            "      <PatternLayout pattern=\"%p %c - %m%n\"/>",
            "    </File>",
            "    <File name=\"behindAsync\" fileName=\"logs/async.log\" immediateFlush=\"false\" bufferedIo=\"true\">",
            "      <PatternLayout pattern=\"%p %c - %m%n\"/>",
            "    </File>",
            "    <Async name=\"async\"><AppenderRef ref=\"behindAsync\"/></Async>",
            "  </Appenders>",
            "  <Loggers>",
            "    <Root level=\"info\">",
            "      <AppenderRef ref=\"written\"/>",
            "      <AppenderRef ref=\"buffered\"/>",
            "      <AppenderRef ref=\"async\"/>",
            "    </Root>",
            "  </Loggers>",
            "</Configuration>",
            ""));
        write(app, "conf/routes", "GET / Application.index\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() { renderText(\"ok\"); }\n"
                + "}\n");
        write(app, "app/jobs/Farewell.java", "package jobs;\n"
                + "@play.jobs.OnApplicationStop\n"
                + "public class Farewell extends play.jobs.Job<Void> {\n"
                + "    @Override\n"
                + "    public void doJob() { play.Logger.info(\"" + FAREWELL + "\"); }\n"
                + "}\n");

        workingCopyServer(app, 0, "-Dprecompile=yes");
        assertThat(new File(app, "precompiled/java/jobs/Farewell.class")).exists();
        deleteTree(new File(app, "logs").toPath());
    }

    /**
     * Run {@code play.server.Server} on {@code app} as a Gradle task does from the working copy
     * (this suite's classpath, with the logging properties every launcher sets) and return its console.
     */
    private static String workingCopyServer(File app, int exitCode, String... jvmArgs) throws Exception {
        File console = new File(app, "server.out");
        List<String> cmd = new ArrayList<>(List.of(java(), "--enable-native-access=ALL-UNNAMED",
                "-DPLAY_SECRET=" + SECRET, "-Dapplication.path=" + app.getAbsolutePath(),
                JUL_MANAGER, "-Dlog4j2.shutdownHookEnabled=false"));
        cmd.addAll(List.of(jvmArgs));
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Server.class.getName()));
        Process p = new ProcessBuilder(cmd).directory(app).redirectErrorStream(true).redirectOutput(console).start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("the JVM ended; console:%n%s", read(console)).isTrue();
        assertThat(p.exitValue()).as("exit code; console:%n%s", read(console)).isEqualTo(exitCode);
        return read(console);
    }

    /** {@code ant jar}: classes/ with the manifest that makes the jar the framework's -javaagent. */
    private static void playJar(File dest) throws IOException {
        Files.createDirectories(dest.getParentFile().toPath());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), "play.classloading.HotswapAgent");
        manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "true");
        Path classes = FRAMEWORK.resolve("classes");
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(dest), manifest);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
    }

    private static List<String> frameworkLibJars() throws IOException {
        try (Stream<Path> lib = Files.list(FRAMEWORK.resolve("lib"))) {
            return lib.filter(jar -> jar.getFileName().toString().endsWith(".jar")).sorted()
                    .map(jar -> jar.toAbsolutePath().toString()).toList();
        }
    }

    /** Run the bundle's {@code ./play} with {@code args} and return what it printed. */
    private static String launch(File bundle, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("./play"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(bundle).redirectErrorStream(true);
        pb.environment().put("PLAY_SECRET", SECRET);
        // the java the suite runs on, ahead of whatever the host has
        pb.environment().put("PATH", Path.of(java()).getParent() + File.pathSeparator + System.getenv("PATH"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("./play %s ended", String.join(" ", args)).isTrue();
        assertThat(p.exitValue()).as("./play %s:%n%s", String.join(" ", args), out).isZero();
        return out;
    }

    /** Wait until {@code GET /} answers, as aot-gen waits before its SIGTERM. */
    private static void awaitOk(int port, Process p, File console) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest index = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            assertThat(p == null || p.isAlive()).as("the JVM is running; console:%n%s", read(console)).isTrue();
            try {
                HttpResponse<String> response = client.send(index, HttpResponse.BodyHandlers.ofString());
                assertThat(response.body()).isEqualTo("ok");
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

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(p);
            }
        }
    }
}
