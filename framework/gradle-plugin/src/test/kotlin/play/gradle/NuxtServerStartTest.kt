package play.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/**
 * PF-213: the Gradle start tasks run Nuxt's server beside Play, as the bundle launcher does
 * (PF-212, BundleLauncherTest), for an application whose frontend/.output holds one (an SSR
 * build, PF-210) when the start is in PROD mode. A DEV start leaves the frontend to `pnpm dev`,
 * which also wants port 3000.
 *
 * Play is a fake framework whose Server listens on --http.port, after -Dpf213.listen.delay ms
 * so that a test can tell waiting for Play from racing it, and with -Dpf213.listen.for ms
 * exits on its own (a foreground playRun that ends). node is a stub that PLAY_NODE names: it
 * logs to node-stub.log in the application what it was started with and whether Play was
 * listening, and exits once its stop file appears or a SIGTERM comes, logging which. Off
 * Windows, where these run, it is named no stop file and gets the SIGTERM (PF-259);
 * PlayStopFileTest covers the Windows branch.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "forks detached JVMs and a #!/bin/sh stub node from TestKit; unverified on Windows, as PrecompiledStartTest")
class NuxtServerStartTest {

    private val fwVersion = "9.9.9-TEST"
    private val nodeRange = "^20.19.0 || >=22.12.0"
    private val modes = "application.name=testapp\napplication.mode=dev\n%prod.application.mode=prod\n"

    private fun app(tmp: File, conf: String = modes): File {
        val fw = fakeFramework(File(tmp, "framework-dist"))
        val app = File(tmp, "app").apply { mkdirs() }
        TestProject.write(
            app,
            play1Block = """
                frameworkVersion.set("$fwVersion")
                frameworkPath.set(file("${fw.absolutePath.replace("\\", "\\\\")}"))
            """.trimIndent()
        )
        File(app, "conf").mkdirs()
        File(app, "conf/routes").writeText("")
        File(app, "conf/application.conf").writeText(conf)
        File(app, "frontend/.output/server").mkdirs()
        File(app, "frontend/.output/server/index.mjs").writeText("// Nuxt's server")
        File(app, "frontend/.output/play1-server.mjs").writeText("// started by the stub node, which never reads it")
        File(app, "frontend/.output/play1.json").writeText("{\"node\": \"$nodeRange\"}\n")
        return app
    }

    private fun stubNode(tmp: File): File = File(tmp, "stub-bin/node").apply {
        parentFile.mkdirs()
        writeText(
            "#!/bin/bash\n" +
                "log=node-stub.log\n" +
                "if [ \"\$2\" = --check ]; then\n" +
                "    echo \"check \$1\" >> \$log\n" +
                "    [ -z \"\$STUB_NODE_TOO_OLD\" ] || { echo \"play: Nuxt's server needs Node ^99.0.0; this is Node 1.0.0\" >&2; exit 1; }\n" +
                "    exit 0\n" +
                "fi\n" +
                "stopped=\n" +
                "trap 'stopped=signal' TERM\n" +
                "play=\"\${NUXT_PLAY_BACKEND_URL#http://}\"\n" +
                "listening() { (exec 3<>\"/dev/tcp/\${play%:*}/\${play##*:}\") 2>/dev/null; }\n" +
                "if listening; then echo play-listening-at-start >> \$log; else echo play-not-listening-at-start >> \$log; fi\n" +
                "for v in NITRO_PORT NITRO_HOST NUXT_PLAY_BACKEND_URL PLAY1_STOP_FILE PF213_FROM_DOTENV; do echo \"ENV:\$v=\${!v}\" >> \$log; done\n" +
                "echo \"started \$1\" >> \$log\n" +
                "while [ -z \"\$stopped\" ]; do\n" +
                "    if [ -n \"\$PLAY1_STOP_FILE\" ] && [ -e \"\$PLAY1_STOP_FILE\" ]; then rm -f \"\$PLAY1_STOP_FILE\"; stopped=file; else sleep 0.05; fi\n" +
                "done\n" +
                "if listening; then echo play-listening-at-stop >> \$log; fi\n" +
                "echo stopped-by-\$stopped >> \$log\n"
        )
        setExecutable(true)
    }

    private fun runner(app: File, node: File, vararg args: String, env: Map<String, String> = emptyMap()): GradleRunner =
        // withEnvironment replaces the build's whole environment, so start from this one.
        TestProject.runner(app, *args).withEnvironment(System.getenv() - "PLAY_FRONTEND_WAIT" + ("PLAY_NODE" to node.absolutePath) + env)

    private fun nodeLog(app: File): List<String> =
        File(app, "node-stub.log").takeIf { it.isFile }?.readLines() ?: emptyList()

    /** Wait up to 30 s for the stub node to have logged [count] lines starting with [line]. */
    private fun awaitNodeLog(app: File, line: String, count: Int = 1) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (nodeLog(app).count { it.startsWith(line) } < count && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(nodeLog(app).count { it.startsWith(line) } >= count, "the stub node never logged '$line' $count times: ${nodeLog(app)}")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun listening(port: Int) = runCatching { Socket("127.0.0.1", port).close() }.isSuccess

    /** Stop whatever a failed test left running, so no JVM or stub outlives it. */
    private fun stopLeftovers(app: File, node: File) {
        if (File(app, "server.pid").exists() || File(app, "server.frontend.pid").exists()) runner(app, node, "playStop").build()
    }

    @Test
    fun `a PROD start runs Nuxt's server once Play listens, pointed at it, and playStop stops it first`(@TempDir tmp: File) {
        val app = app(tmp, modes + "frontend.port=3457\n")
        File(app, "certs").mkdirs()
        File(app, "certs/.env").writeText("PF213_FROM_DOTENV=from certs/.env\n")
        val node = stubNode(tmp)
        val port = freePort()
        try {
            // Play takes 1.5 s to listen, so Nuxt's server can only find it listening by waiting.
            val start = runner(app, node, "playStart", "-PplayId=prod", "-PhttpPort=$port", "-PjvmArgs=-Dpf213.listen.delay=1500").build()

            assertTrue("~ OK, Nuxt's server is started" in start.output, start.output)
            awaitNodeLog(app, "started")
            val log = nodeLog(app)
            assertEquals("check ${File(app.canonicalFile, "frontend/.output/play1-server.mjs").path}", log.first(), "the Node check comes first: $log")
            assertTrue("play-listening-at-start" in log, "Nuxt's server started before Play listened: $log")
            for (env in listOf(
                "NITRO_PORT=3457", "NITRO_HOST=127.0.0.1", "NUXT_PLAY_BACKEND_URL=http://127.0.0.1:$port",
                // PF-259: no stop file off Windows
                "PLAY1_STOP_FILE=", "PF213_FROM_DOTENV=from certs/.env",
            )) assertTrue("ENV:$env" in log, "Nuxt's server should get $env: $log")

            val status = runner(app, node, "playStatus", "-PhttpPort=$port").build().output
            assertTrue("~ Nuxt's server is running (pid " in status, status)
            val pid = runner(app, node, "playPid").build().output
            assertTrue("~ PID of Nuxt's server is ${File(app, "server.frontend.pid").readText().trim()}" in pid, pid)

            val stop = runner(app, node, "playStop").build().output

            val nuxtStopped = stop.indexOf("~ OK, Nuxt's server is stopped")
            val playStopped = stop.indexOf("~ OK, ${app.canonicalPath} is stopped")
            assertTrue(nuxtStopped in 0 until playStopped, "Nuxt's server stops first, then Play:\n$stop")
            assertTrue("play-listening-at-stop" in nodeLog(app), "Play went down before Nuxt's server: ${nodeLog(app)}")
            assertTrue("stopped-by-signal" in nodeLog(app), "${nodeLog(app)}")
            for (left in listOf("server.pid", "server.frontend.pid", "server.frontend.pid.stop")) {
                assertFalse(File(app, left).exists(), "$left should be gone after playStop")
            }
        } finally {
            stopLeftovers(app, node)
        }
    }

    @Test
    fun `a DEV start leaves the frontend to pnpm dev and never runs node`(@TempDir tmp: File) {
        val app = app(tmp)
        val node = stubNode(tmp)
        try {
            val start = runner(app, node, "playStart", "-PhttpPort=${freePort()}").build()

            assertTrue("this is a DEV start: run the frontend with `cd frontend && pnpm dev`" in start.output, start.output)
            assertFalse(File(app, "server.frontend.pid").exists())
            assertEquals(emptyList<String>(), nodeLog(app), "node must not run for a DEV start")
        } finally {
            stopLeftovers(app, node)
        }
    }

    @Test
    fun `a PROD start refuses before starting anything when Node is missing or too old`(@TempDir tmp: File) {
        val app = app(tmp)
        val node = stubNode(tmp)
        val port = freePort()
        try {
            val missing = runner(app, File(tmp, "no-such-node"), "playStart", "-PplayId=prod", "-PhttpPort=$port").buildAndFail()
            assertTrue("needs Node $nodeRange" in missing.output && "could not be run" in missing.output, missing.output)
            assertFalse(File(app, "server.pid").exists(), "Play must not have been started")

            val old = runner(app, node, "playStart", "-PplayId=prod", "-PhttpPort=$port", env = mapOf("STUB_NODE_TOO_OLD" to "1")).buildAndFail()
            assertTrue("needs Node ^99.0.0; this is Node 1.0.0" in old.output, old.output)
            assertFalse(File(app, "server.pid").exists(), "Play must not have been started")
            assertEquals(listOf("check ${File(app.canonicalFile, "frontend/.output/play1-server.mjs").path}"), nodeLog(app), "nothing but the check ran")
            assertFalse(listening(port))
        } finally {
            stopLeftovers(app, node)
        }
    }

    @Test
    fun `playRestart stops Nuxt's server first and starts both again`(@TempDir tmp: File) {
        val app = app(tmp)
        val node = stubNode(tmp)
        val port = freePort()
        try {
            runner(app, node, "playStart", "-PplayId=prod", "-PhttpPort=$port").build()
            awaitNodeLog(app, "started")
            val first = File(app, "server.frontend.pid").readText().trim()

            val restart = runner(app, node, "playRestart", "-PplayId=prod", "-PhttpPort=$port", "-PjvmArgs=-Dpf213.listen.delay=1000").build()

            assertTrue("~ OK, Nuxt's server is stopped" in restart.output && "~ OK, Nuxt's server is started" in restart.output, restart.output)
            awaitNodeLog(app, "started", count = 2)
            val log = nodeLog(app)
            assertTrue(log.indexOf("stopped-by-signal") in 0 until log.lastIndexOf(log.last { it.startsWith("started") }), "$log")
            assertEquals("play-listening-at-start", log.filter { it.startsWith("play-") && "start" in it }.last(), "the new one waited for the new Play: $log")
            assertNotEquals(first, File(app, "server.frontend.pid").readText().trim())
        } finally {
            stopLeftovers(app, node)
        }
    }

    @Test
    fun `a PROD playRun runs Nuxt's server beside the foreground run and stops it when Play stops`(@TempDir tmp: File) {
        val app = app(tmp)
        val node = stubNode(tmp)
        val port = freePort()

        // Play listens after 1 s and exits on its own 4 s later: the end of the foreground run.
        val run = runner(app, node, "playRun", "-PplayId=prod", "-PhttpPort=$port", "-PjvmArgs=-Dpf213.listen.delay=1000 -Dpf213.listen.for=4000").build()

        val log = nodeLog(app)
        assertTrue("play-listening-at-start" in log, "Nuxt's server started before Play listened: $log\n${run.output}")
        assertTrue("ENV:NUXT_PLAY_BACKEND_URL=http://127.0.0.1:$port" in log, "$log")
        assertTrue("stopped-by-signal" in log, "Nuxt's server was not stopped when the run ended: $log\n${run.output}")
        assertTrue("~ OK, Nuxt's server is stopped" in run.output, run.output)
    }

    @Test
    fun `a PROD playRun that fails still stops Nuxt's server when the build ends`(@TempDir tmp: File) {
        val app = app(tmp)
        val node = stubNode(tmp)
        val port = freePort()

        // Play exits with status 3: the task fails, its doLast never runs, and only the build
        // service's close() is left to stop Nuxt's server.
        runner(app, node, "playRun", "-PplayId=prod", "-PhttpPort=$port", "-PjvmArgs=-Dpf213.listen.delay=1000 -Dpf213.listen.for=3000 -Dpf213.exit=3").buildAndFail()

        val log = nodeLog(app)
        assertTrue("play-listening-at-start" in log, "$log")
        assertTrue("stopped-by-signal" in log, "Nuxt's server outlived the failed run: $log")
    }

    /**
     * A framework distribution whose play.server.Server listens on --http.port, after
     * -Dpf213.listen.delay ms, until the JVM is told to stop or, with -Dpf213.listen.for,
     * for that many ms, then exits with -Dpf213.exit (0 unless set); a loadable -javaagent besides.
     */
    private fun fakeFramework(root: File): File {
        val source = File(root.parentFile, "fake-src/play/server/Server.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package play.server;
                import java.io.IOException;
                import java.net.ServerSocket;
                import java.net.Socket;
                public class Server {
                    public static void premain(String args, java.lang.instrument.Instrumentation inst) {}
                    public static void main(String[] args) throws Exception {
                        int port = 0;
                        for (String arg : args) {
                            if (arg.startsWith("--http.port=")) port = Integer.parseInt(arg.substring("--http.port=".length()));
                        }
                        Thread.sleep(Long.getLong("pf213.listen.delay", 0));
                        ServerSocket server = new ServerSocket(port);
                        Thread accept = new Thread(() -> {
                            while (true) {
                                try (Socket socket = server.accept()) {
                                    socket.getOutputStream().write(
                                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes());
                                } catch (IOException e) {
                                    if (server.isClosed()) return;
                                }
                            }
                        });
                        accept.setDaemon(true);
                        accept.start();
                        long listenFor = Long.getLong("pf213.listen.for", -1);
                        if (listenFor < 0) accept.join();
                        Thread.sleep(listenFor);
                        server.close();
                        int status = Integer.getInteger("pf213.exit", 0);
                        if (status != 0) System.exit(status);
                    }
                }
                """.trimIndent()
            )
        }
        val classes = File(root.parentFile, "fake-classes").apply { mkdirs() }
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK, not a JRE")
        assertEquals(0, javac.run(null, null, null, "--release", "25", "-d", classes.absolutePath, source.absolutePath), "compiling the fake framework failed")
        File(root, "framework/lib").mkdirs()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Premain-Class")] = "play.server.Server"
        }
        JarOutputStream(File(root, "framework/play-$fwVersion.jar").outputStream(), manifest).use { jar ->
            classes.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(classes).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return root
    }
}
