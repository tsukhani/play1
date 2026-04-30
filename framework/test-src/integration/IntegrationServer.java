package integration;

import java.io.File;

import play.Play;
import play.server.Server;

/**
 * Idempotent helper for tests that need a real Netty server bound (HTTPS, h2, h3).
 * The integration suite runs all *Test classes in one JVM (see build.xml's
 * &lt;junitlauncher&gt; with one &lt;fork&gt;), so two test classes both calling
 * {@code new Server(...)} from {@code @BeforeAll} would try to bind the same ports
 * twice. Centralizing the bootstrap behind {@link #ensureStarted()} makes the
 * second call a no-op.
 *
 * <p>No matching {@code shutdown()} — the JVM shutdown hook registered in
 * {@code Server.registerForShutdown} drains the EventLoopGroups when the test JVM
 * exits, which is when the integration target finishes anyway.
 */
final class IntegrationServer {

    private static volatile boolean started;

    private IntegrationServer() {}

    static void ensureStarted() {
        if (started) return;
        synchronized (IntegrationServer.class) {
            if (started) return;
            synchronized (Play.class) {
                if (!Play.started) {
                    File testApp = new File(System.getProperty("user.dir"), "test-src/integration/testapp");
                    if (!testApp.isDirectory()) {
                        throw new RuntimeException("Integration testapp not found at " + testApp.getAbsolutePath());
                    }
                    Play.init(testApp, "test");
                    if (!Play.started) {
                        Play.start();
                    }
                }
            }
            // Server constructor binds synchronously (syncUninterruptibly), so listeners
            // are up by the time this returns.
            new Server(new String[0]);
            started = true;
        }
    }
}
