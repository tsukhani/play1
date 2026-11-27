package play.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import play.Logger;

/**
 * PF-243: a stop that runs the shutdown hooks where no signal can. On Windows no signal
 * reaches a native {@code java.exe} from another process: {@code ProcessHandle.destroy()}, and
 * {@code kill} from Git Bash, MSYS2 or Cygwin, terminate it outright, so a stop ran no
 * shutdown hook at all, no server drain, no {@code onApplicationStop}, no H2 close and no log4j
 * shutdown. So every launcher names a file beside the pid file in {@value #PROPERTY}
 * ({@code server.pid.stop} for {@code server.pid}), and a stop on Windows writes it: once it
 * appears, the JVM removes it and calls {@code System.exit(0)}, which runs the hooks as a
 * SIGTERM does, {@code Play.shutdownHook} included. macOS and Linux are still stopped with
 * SIGTERM. Nuxt's server beside Play stops the same way (PF-213).
 *
 * <p>The file is looked for every {@value #POLL_MS} ms by a daemon platform thread, from the start
 * of {@code Server.main}, so a stop during a long start ends the JVM too, as a SIGTERM would.
 * Not a virtual thread: one made before {@code Play.init} would start the virtual-thread
 * scheduler before anything the application does at start could set its properties. Not a
 * {@code WatchService} either, which polls on macOS anyway. A file already there when the
 * watch begins stops the JVM as well: that is a stop sent before the JVM was watching, and the
 * launchers remove a stale one before they start a JVM.
 */
public final class StopFile {

    /** The system property the launchers name the file in. */
    public static final String PROPERTY = "play.stopFile";

    static final long POLL_MS = 100;

    private StopFile() {
    }

    /** Watch the file {@value #PROPERTY} names, if it names one; null when it does not. */
    static Thread watchFromProperty() {
        String name = System.getProperty(PROPERTY);
        if (name == null || name.isBlank()) {
            return null;
        }
        return watch(Path.of(name.trim()).toAbsolutePath(), () -> System.exit(0));
    }

    /**
     * Run {@code stop} once {@code file} exists, after removing the file, on a daemon thread of its
     * own. Interrupting the thread ends the watch.
     */
    static Thread watch(Path file, Runnable stop) {
        return Thread.ofPlatform().daemon().name("play-stop-file").start(() -> {
            try {
                while (!Files.exists(file)) {
                    TimeUnit.MILLISECONDS.sleep(POLL_MS);
                }
            } catch (InterruptedException e) {
                return;
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException | SecurityException e) {
                // the launcher removes it once the JVM has gone, and a start removes a stale one
            }
            Logger.info("Stop file %s found: stopping", file);
            stop.run();
        });
    }
}
