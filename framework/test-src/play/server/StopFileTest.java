package play.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PF-243: the JVM's half of a stop without a signal. The file appears, the watch removes it and
 * stops the JVM. A real JVM's {@code System.exit(0)} and its shutdown hooks are in
 * {@code integration.H2ShutdownTest} and {@code integration.ShutdownLoggingTest}.
 */
public class StopFileTest {

    @AfterEach
    public void clearProperty() {
        System.clearProperty(StopFile.PROPERTY);
    }

    @Test
    public void theFileAppearingStopsAndIsRemoved(@TempDir File dir) throws Exception {
        Path file = dir.toPath().resolve("server.pid.stop");
        CountDownLatch stopped = new CountDownLatch(1);
        Thread watch = StopFile.watch(file, stopped::countDown);

        Thread.sleep(3 * StopFile.POLL_MS);
        assertThat(stopped.getCount()).as("stopped before the file was there").isEqualTo(1);

        Files.writeString(file, "");
        assertThat(stopped.await(5, TimeUnit.SECONDS)).as("the stop ran").isTrue();
        watch.join(5_000);
        assertThat(file).as("the watch removes the file it answered").doesNotExist();
        assertThat(watch.isDaemon()).as("the watch keeps no JVM alive").isTrue();
        assertThat(watch.isVirtual()).as("no virtual thread before Play.init").isFalse();
        assertThat(watch.getName()).isEqualTo("play-stop-file");
    }

    @Test
    public void aFileThatIsThereWhenTheWatchBeginsStopsAtOnce(@TempDir File dir) throws Exception {
        // a stop sent before the JVM was watching; the launchers remove a stale file before a start
        Path file = Files.writeString(dir.toPath().resolve("server.pid.stop"), "");
        CountDownLatch stopped = new CountDownLatch(1);
        StopFile.watch(file, stopped::countDown);
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void anInterruptEndsTheWatchWithoutAStop(@TempDir File dir) throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        Thread watch = StopFile.watch(dir.toPath().resolve("server.pid.stop"), stopped::countDown);
        watch.interrupt();
        watch.join(5_000);
        assertThat(watch.isAlive()).isFalse();
        assertThat(stopped.getCount()).isEqualTo(1);
    }

    @Test
    public void noPropertyNoWatch() {
        System.clearProperty(StopFile.PROPERTY);
        assertThat(StopFile.watchFromProperty()).isNull();
        System.setProperty(StopFile.PROPERTY, "  ");
        assertThat(StopFile.watchFromProperty()).isNull();
    }

    @Test
    public void thePropertyNamesTheFileWatched(@TempDir File dir) throws Exception {
        // a relative name resolves against the working directory, as the launchers' pid file does
        Path file = dir.toPath().resolve("server.pid.stop");
        System.setProperty(StopFile.PROPERTY, file.toString());
        Thread watch = StopFile.watchFromProperty();
        try {
            assertThat(watch).isNotNull();
            assertThat(watch.isAlive()).isTrue();
        } finally {
            // never let it reach System.exit in the suite's JVM
            watch.interrupt();
            watch.join(5_000);
        }
        assertThat(watch.isAlive()).isFalse();
    }
}
