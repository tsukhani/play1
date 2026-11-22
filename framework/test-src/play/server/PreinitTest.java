package play.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * PF-239: Server.main initializes the classes of the bind, the first request and the routes on
 * a thread of its own. A class a library renames would leave that thread with nothing to do
 * and no error, so every name it initializes must still be a class.
 */
public class PreinitTest {

    @Test
    public void everyClassThePreinitializerNamesExists() {
        ClassLoader loader = Server.class.getClassLoader();
        for (String name : Server.PREINIT) {
            assertThatCode(() -> Class.forName(name, false, loader)).as(name).doesNotThrowAnyException();
        }
    }

    @Test
    public void thePreinitializerRunsOnADaemonThreadAndEnds() throws InterruptedException {
        Thread thread = Server.preinitialize();
        assertThat(thread.isDaemon()).isTrue();
        thread.join(30_000);
        assertThat(thread.isAlive()).isFalse();
    }

    /**
     * A handler that the default pipeline leaves out is work the start would not otherwise do.
     * HttpContentCompressor was one: its initialization extracted and loaded brotli4j's native
     * library at every start.
     */
    @Test
    public void everyHandlerThePreinitializerNamesIsInTheDefaultPipeline() throws ClassNotFoundException {
        List<String> pipeline = Arrays.asList(HttpServerPipelineFactory.DEFAULT_PIPELINE.split(","));
        ClassLoader loader = Server.class.getClassLoader();
        for (String name : Server.PREINIT) {
            if (ChannelHandler.class.isAssignableFrom(Class.forName(name, false, loader))) {
                assertThat(pipeline).as("%s is a handler the default play.netty.pipeline does not use", name).contains(name);
            }
        }
    }

    /** PF-173 keeps brotli4j's native library off a start whose pipeline does not compress. */
    @Test
    public void thePreinitializerLoadsNoBrotliNativeLibrary() throws Exception {
        Process p = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xlog:library=info", "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("probe JVM exited").isTrue();
        assertThat(p.exitValue()).as("probe JVM exit code; output:%n%s", out).isZero();
        assertThat(out).as("the probe ran the preinitializer").contains("PREINIT DONE");
        assertThat(out.toLowerCase()).as("native libraries the preinitializer loaded:%n%s", out).doesNotContain("brotli");
    }

    /** Runs the preinitializer in a JVM of its own, so nothing else has loaded a library yet. */
    public static class Probe {
        public static void main(String[] args) throws InterruptedException {
            Thread thread = Server.preinitialize();
            thread.join(30_000);
            System.out.println(thread.isAlive() ? "PREINIT STILL RUNNING" : "PREINIT DONE");
        }
    }
}
