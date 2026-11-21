package play.test;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import play.Play;
import play.exceptions.UnexpectedException;
import play.server.Server;

/**
 * PF-230: starts the application in a JVM that runs its tests on the JUnit Platform, once and
 * before the first test class is loaded, when the JVM is started with
 * {@code -Dplay.test.startApplication=true}, as the play1 plugin starts the JVMs of Gradle's
 * test task. It is registered with {@code ServiceLoader}, so every JUnit Platform launch with
 * play's jar on its classpath opens a session through it, the {@code /@tests} runner's included,
 * and does nothing there.
 *
 * <ul>
 * <li>The JVM takes the lowest number from 1 that no other JVM of the application's test runs
 * holds, and {@code play.test.jvm} holds it as a system property before the configuration is
 * read, so that {@code ${play.test.jvm:0}} in a {@code %test.} value gives each JVM a path of its
 * own: a lock on {@code tmp/test-jvms/<n>.lock}, which the system releases when the JVM
 * ends.</li>
 * <li>Play starts in test mode, as {@link PlayJUnitExtension} starts it, with test/ compiled
 * beside app/.</li>
 * <li>The thread's context class loader becomes Play's. A runner loads the classes it runs
 * after the session has opened, and Gradle loads them through the loader the thread had then,
 * so the test classes, and the application classes they reach, are the ones Play compiled and
 * enhanced, as under {@code /@tests}, and not Gradle's own compile output.</li>
 * <li>The HTTP server listens, on a port the system picks, whatever {@code http.port} the
 * configuration names, so that no two JVMs of a run share one; {@code http.port} then holds it,
 * where a test reads its JVM's port. HTTPS, when the configuration has a port for it, gets a
 * picked port too, in {@code https.port}.</li>
 * </ul>
 *
 * <p>
 * Each test class then runs in a job of its own, as {@code /@tests} runs it: see
 * {@link PlayJUnitExtension#useCustomRunner}. Play stops at the JVM's exit, through the shutdown
 * hook every Play JVM has.
 */
public class PlayTestSession implements LauncherSessionListener {

    /** The system property that turns this on. */
    public static final String START_APPLICATION = "play.test.startApplication";

    /** The system property that holds the number of this JVM among those of the test runs. */
    public static final String JVM_NUMBER = "play.test.jvm";

    // held until the JVM ends: a lock is released when its channel is closed, which a collected
    // channel is
    private static FileLock jvmNumberLock;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (!Boolean.getBoolean(START_APPLICATION)) {
            return;
        }
        synchronized (Play.class) {
            if (Play.started) {
                return;
            }
            takeJvmNumber(new File(System.getProperty("application.path", "."), "tmp/test-jvms"));
            PlayJUnitExtension.start();
            String httpsPort = Play.configuration.getProperty("https.port", "-1").trim();
            new Server(httpsPort.equals("-1") ? new String[] { "--http.port=0" }
                    : new String[] { "--http.port=0", "--https.port=0" });
        }
        Thread.currentThread().setContextClassLoader(Play.classloader);
    }

    private static void takeJvmNumber(File locks) {
        // the other JVMs of the run create it at the same moment
        locks.mkdirs();
        if (!locks.isDirectory()) {
            throw new UnexpectedException("Cannot create " + locks);
        }
        try {
            for (int n = 1;; n++) {
                FileChannel channel = FileChannel.open(new File(locks, n + ".lock").toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    jvmNumberLock = lock;
                    System.setProperty(JVM_NUMBER, String.valueOf(n));
                    return;
                }
                channel.close();
            }
        } catch (IOException e) {
            throw new UnexpectedException("Cannot take a number for this test JVM in " + locks, e);
        }
    }
}
