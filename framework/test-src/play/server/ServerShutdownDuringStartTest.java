package play.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.netty.channel.ChannelException;
import io.netty.handler.codec.quic.Quic;

/**
 * PF-188: a SIGTERM during start ran the shutdown drain while {@code Server.<init>} was still
 * binding. The drain terminated every event loop registered so far, and the constructor then
 * bound on a terminated one: Netty refused it with a {@code RejectedExecutionException}. A
 * training run ({@code play aot-gen}) hit this every time, because it sends its SIGTERM as soon
 * as the HTTP port has answered, and trained a cache without HTTP/3.
 *
 * <p>Each case boots a scratch application through the real entry point in a child JVM: the
 * drain is static, so running it here would close the listeners of anything else in this JVM,
 * and a failed start exits the JVM. {@link Server#beforeBind} holds the constructor at a bind
 * while the probe begins the drain on another thread, so the window is held open
 * deterministically rather than hit by timing.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the drain under test is what a SIGTERM runs")
public class ServerShutdownDuringStartTest {

    /** What the drain logs when the constructor did not finish binding within its bound. */
    private static final String GAVE_UP = "stopped waiting for the server to finish binding";

    /**
     * Boots through {@code Server.main}, drains the server and reports what is left listening.
     * {@code -Dprobe.drainAt=<listener>} begins the drain on another thread just before that
     * bind; without it the drain runs once the start is over. {@code -Dprobe.refuse=<listener>}
     * makes that bind fail instead, which ends the start in {@code Play.fatalServerErrorOccurred()}.
     */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            String drainAt = System.getProperty("probe.drainAt");
            String refuse = System.getProperty("probe.refuse");
            Thread[] drain = new Thread[1];
            Server.beforeBind = listener -> {
                if (listener.equals(refuse)) {
                    throw new ChannelException("refused by the probe");
                }
                if (listener.equals(drainAt)) {
                    // the HTTP port answers, which is what aot-gen waits for before its SIGTERM
                    try (Socket http = new Socket(InetAddress.getLoopbackAddress(), Server.httpPort)) {
                        System.out.println("PROBE HTTP answers before the " + listener + " bind");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    drain[0] = new Thread(Server::gracefulShutdown, "probe-drain");
                    drain[0].start();
                    // let the start go on once the drain has run to its end, or waits for the binds
                    while (drain[0].isAlive() && !waitsForTheBinds(drain[0])) {
                        Thread.onSpinWait();
                    }
                }
            };
            Server.main(args);

            if (drain[0] == null) {
                long began = System.nanoTime();
                Server.gracefulShutdown();
                System.out.println("PROBE drain after start took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) + " ms");
            } else {
                drain[0].join();
            }
            System.out.println("PROBE HTTP/3 bound: " + Server.http3BoundOnHttpsPort);
            System.out.println("PROBE closed: HTTP=" + tcpFree(Server.httpPort) + " HTTPS=" + tcpFree(Server.httpsPort)
                    + " HTTP/3=" + udpFree(Server.httpsPort));
            System.exit(0);
        }

        /** Whether the drain is parked on the latch it waits on while the constructor binds. */
        private static boolean waitsForTheBinds(Thread drain) {
            return drain.getState() == Thread.State.TIMED_WAITING && Arrays.stream(drain.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(CountDownLatch.class.getName()));
        }

        private static boolean tcpFree(int port) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private static boolean udpFree(int port) {
            try (DatagramSocket socket = new DatagramSocket(port)) {
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTPS", "HTTP/3"})
    public void aShutdownBegunDuringStartLetsTheBindsFinishAndClosesEveryListener(String drainAt, @TempDir File app)
            throws Exception {
        boolean quic = Quic.isAvailable();
        assumeTrue(quic || !drainAt.equals("HTTP/3"), "no native QUIC on this platform, so no HTTP/3 bind to begin it before");

        List<String> console = boot(app, 0, "-Dprobe.drainAt=" + drainAt);

        assertThat(console).contains("PROBE HTTP answers before the " + drainAt + " bind");
        assertThat(console).as("no bind was refused").noneMatch(line -> line.contains("RejectedExecutionException"));
        assertThat(console).anyMatch(line -> line.contains("Listening for HTTPS on port"));
        if (quic) {
            assertThat(console).anyMatch(line -> line.contains("Listening for HTTP/3 on UDP port"));
            assertThat(console).contains("PROBE HTTP/3 bound: true");
        }
        assertThat(console).as("the drain closed the listeners bound after it began")
                .contains("PROBE closed: HTTP=true HTTPS=true HTTP/3=true");
        assertThat(console).noneMatch(line -> line.contains(GAVE_UP));
    }

    @Test
    public void aShutdownAfterTheStartIsNotHeldUpByTheWait(@TempDir File app) throws Exception {
        List<String> console = boot(app, 0);

        String took = console.stream().filter(line -> line.startsWith("PROBE drain after start took ")).findFirst()
                .orElseThrow(() -> new AssertionError("the probe never drained:\n" + String.join("\n", console)));
        long ms = Long.parseLong(took.replaceAll("\\D", ""));
        assertThat(ms).as("the drain waited for binds that were already over").isLessThan(Server.BIND_WAIT_MS);
        assertThat(console).contains("PROBE closed: HTTP=true HTTPS=true HTTP/3=true");
        assertThat(console).noneMatch(line -> line.contains(GAVE_UP));
    }

    @Test
    public void aStartThatEndsInAFatalErrorExitsWithoutWaitingOutTheBound(@TempDir File app) throws Exception {
        // The JVM exits from inside the constructor, which runs the drain on the hook thread
        // while the constructor never returns: a drain that waited for it would wait its bound.
        List<String> console = boot(app, 255, "-Dprobe.refuse=HTTPS");

        assertThat(console).anyMatch(line -> line.contains("Could not bind on port"));
        assertThat(console).noneMatch(line -> line.contains(GAVE_UP));
    }

    /**
     * Run {@link Probe} on a scratch application with HTTP and HTTPS, which brings HTTP/3 where
     * the platform has native QUIC, and return its console, stdout and stderr together.
     */
    private static List<String> boot(File app, int exitCode, String... jvmArgs) throws Exception {
        write(app, "conf/application.conf", "application.name=shutdown-during-start\napplication.mode=prod\n"
                + "application.secret=${PLAY_SECRET}\n");
        write(app, "conf/routes", "GET / Application.index\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() { renderText(\"ok\"); }\n"
                + "}\n");
        // the integration fixture's self-signed pair, where both TLS listeners look by default
        Path certs = Path.of(System.getProperty("user.dir"), "test-src/integration/testapp/certs");
        for (String name : List.of("host.cert", "host.key")) {
            Files.createDirectories(app.toPath().resolve("certs"));
            Files.copy(certs.resolve(name), app.toPath().resolve("certs/" + name));
        }

        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-DPLAY_SECRET=shutdownduringstarttestsecret1234567890shutdownduringstarttest12",
            // as every launcher starts Play (PF-189): log4j's own hook would stop logging while
            // the drain still runs, and a drain that waited out its bound would log that unseen
            "-Dlog4j2.shutdownHookEnabled=false",
            "-Dapplication.path=" + app.getAbsolutePath()));
        cmd.addAll(List.of(jvmArgs));
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(),
            "--http.port=" + freePort(), "--https.port=" + freePort()));

        File console = new File(app, "console.log");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(console).start();
        boolean exited = p.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly().waitFor();
        }
        String out = Files.readString(console.toPath(), StandardCharsets.UTF_8);
        assertThat(exited).as("probe JVM exited; console:%n%s", out).isTrue();
        assertThat(p.exitValue()).as("probe JVM exit code; console:%n%s", out).isEqualTo(exitCode);
        return out.lines().toList();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void write(File app, String path, String content) throws IOException {
        Path file = app.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
