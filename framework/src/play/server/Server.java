package play.server;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.Quic;
import play.Invoker;
import play.Logger;
import play.Play;
import play.Play.Mode;
import play.libs.IO;
import play.server.quic.Http3ServerInitializer;
import play.server.quic.Http3SslContextFactory;
import play.server.ssl.SslHttpServerPipelineFactory;

public class Server {

    public static int httpPort;
    public static int httpsPort;

    /**
     * PF-57 + flag-removal: set to {@code true} after a successful UDP-bound h3 listener
     * on {@link #httpsPort}. PlayHandler reads this when deciding whether to emit the
     * {@code Alt-Svc} header — we only advertise h3 if it actually bound, so platforms
     * without native QUIC don't lure clients into UDP timeouts.
     */
    public static volatile boolean http3BoundOnHttpsPort = false;

    public static final String PID_FILE = "server.pid";

    /**
     * Tracks every {@link EventLoopGroup} created during bind so the JVM shutdown hook can
     * gracefully drain accept/IO threads. Without this, the daemon NIO threads outlive the
     * application — harmless on real JVM exit but a real leak in DEV hot-reload restarts and
     * test harnesses that reinstantiate {@link Server} repeatedly within one JVM.
     */
    private static final List<EventLoopGroup> eventLoopGroups = new ArrayList<>();
    private static volatile boolean shutdownHookRegistered = false;

    /**
     * PF-119 follow-up: the bound listen channels (TCP accept sockets for HTTP/HTTPS plus the
     * HTTP/3 UDP socket), captured at bind so {@link #stopAccepting()} can close them — halting
     * new connections — while the worker {@link EventLoopGroup}s stay alive long enough for
     * in-flight requests to finish and flush their responses.
     */
    private static final List<Channel> serverChannels = new ArrayList<>();

    public Server(String[] args) {

        System.setProperty("file.encoding", "utf-8");
        Properties p = Play.configuration;

        httpPort = Integer.parseInt(getOpt(args, "http.port", p.getProperty("http.port", "-1")));
        httpsPort = Integer.parseInt(getOpt(args, "https.port", p.getProperty("https.port", "-1")));

        if (httpPort == -1 && httpsPort == -1) {
            httpPort = 9000;
        }

        if (httpPort == httpsPort) {
            Logger.error("Could not bind on https and http on the same port " + httpPort);
            Play.fatalServerErrorOccurred();
        }

        InetAddress address = null;
        InetAddress secureAddress = null;
        try {
            if (p.getProperty("http.address") != null) {
                address = InetAddress.getByName(p.getProperty("http.address"));
            } else if (System.getProperties().containsKey("http.address")) {
                address = InetAddress.getByName(System.getProperty("http.address"));
            }

        } catch (Exception e) {
            Logger.error(e, "Could not understand http.address");
            Play.fatalServerErrorOccurred();
        }
        try {
            if (p.getProperty("https.address") != null) {
                secureAddress = InetAddress.getByName(p.getProperty("https.address"));
            } else if (System.getProperties().containsKey("https.address")) {
                secureAddress = InetAddress.getByName(System.getProperty("https.address"));
            }
        } catch (Exception e) {
            Logger.error(e, "Could not understand https.address");
            Play.fatalServerErrorOccurred();
        }

        try {
            if (httpPort != -1) {
                // Boss only accepts; one thread per bound port suffices. Worker count honours
                // play.netty.maxThreads if set (legacy Netty 3 toggle), otherwise Netty's default
                // (2 × cores). 0 / unset / unparseable → default.
                EventLoopGroup bossGroup = new NioEventLoopGroup(1);
                EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads());
                registerForShutdown(bossGroup, workerGroup);
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new HttpServerPipelineFactory())
                        .childOption(ChannelOption.TCP_NODELAY, true);

                serverChannels.add(bootstrap.bind(new InetSocketAddress(address, httpPort)).syncUninterruptibly().channel());

                if (Play.mode == Mode.DEV) {
                    if (address == null) {
                        Logger.info("Listening for HTTP on port %s (Waiting a first request to start) ...", httpPort);
                    } else {
                        Logger.info("Listening for HTTP at %2$s:%1$s (Waiting a first request to start) ...", httpPort, address);
                    }
                } else {
                    if (address == null) {
                        Logger.info("Listening for HTTP on port %s ...", httpPort);
                    } else {
                        Logger.info("Listening for HTTP at %2$s:%1$s  ...", httpPort, address);
                    }
                }

            }

        } catch (ChannelException e) {
            Logger.error("Could not bind on port " + httpPort, e);
            Play.fatalServerErrorOccurred();
        }

        try {
            if (httpsPort != -1) {
                EventLoopGroup bossGroup = new NioEventLoopGroup(1);
                EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads());
                registerForShutdown(bossGroup, workerGroup);
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new SslHttpServerPipelineFactory())
                        .childOption(ChannelOption.TCP_NODELAY, true);

                serverChannels.add(bootstrap.bind(new InetSocketAddress(secureAddress, httpsPort)).syncUninterruptibly().channel());

                if (Play.mode == Mode.DEV) {
                    if (secureAddress == null) {
                        Logger.info("Listening for HTTPS on port %s (Waiting a first request to start) ...", httpsPort);
                    } else {
                        Logger.info("Listening for HTTPS at %2$s:%1$s (Waiting a first request to start) ...", httpsPort, secureAddress);
                    }
                } else {
                    if (secureAddress == null) {
                        Logger.info("Listening for HTTPS on port %s ...", httpsPort);
                    } else {
                        Logger.info("Listening for HTTPS at %2$s:%1$s  ...", httpsPort, secureAddress);
                    }
                }

            }

        } catch (ChannelException e) {
            Logger.error("Could not bind on port " + httpsPort, e);
            Play.fatalServerErrorOccurred();
        }

        // PF-57 + flag-removal: HTTP/3 over QUIC, implicitly activated when HTTPS is
        // configured (no separate play.http3.enabled flag). Binds a UDP listener on the
        // same port number as HTTPS — TCP and UDP have separate port spaces, so TCP:9443
        // and UDP:9443 coexist. This is the standard h3 deployment shape; clients
        // discover the h3 endpoint via the Alt-Svc header on TCP HTTPS responses
        // (PlayHandler) and switch to QUIC for subsequent requests.
        //
        // Graceful degradation: on platforms without a native-quic artifact
        // (linux-riscv64, plus any future arch we haven't shipped a binary for) or when
        // Netty's QUIC JNI fails to link, the framework logs a WARN and skips the UDP
        // listener. HTTPS+h2+h1.1 keep working normally on TCP. The matching Alt-Svc
        // suppression in PlayHandler reads the same Server.http3BoundOnHttpsPort flag.
        if (httpsPort != -1) {
            if (Quic.isAvailable()) {
                try {
                    EventLoopGroup quicGroup = new NioEventLoopGroup(1);
                    registerForShutdown(quicGroup);
                    ChannelHandler quicCodec = Http3.newQuicServerCodecBuilder()
                            .sslContext(Http3SslContextFactory.getServerContext())
                            // Generous stream + data limits matching Netty's HTTP/3 example. PlayHandler
                            // already enforces play.netty.maxContentLength via StreamChunkAggregator;
                            // these limits gate the QUIC flow-control window before that.
                            .maxIdleTimeout(30_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .initialMaxData(10_000_000)
                            .initialMaxStreamDataBidirectionalLocal(1_000_000)
                            .initialMaxStreamDataBidirectionalRemote(1_000_000)
                            .initialMaxStreamsBidirectional(100)
                            .initialMaxStreamsUnidirectional(100)
                            // PF-57 phase 1+2 ships with InsecureQuicTokenHandler — accepts any retry
                            // token without cryptographic validation. Suitable for dev + non-DDoS-exposed
                            // deployments. Phase 3 follow-up: replace with a token handler keyed by a
                            // server-side secret so retry tokens can't be forged.
                            .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                            .handler(new Http3ServerInitializer())
                            .build();
                    Bootstrap bootstrap = new Bootstrap();
                    bootstrap.group(quicGroup)
                            .channel(NioDatagramChannel.class)
                            .handler(quicCodec);
                    serverChannels.add(bootstrap.bind(new InetSocketAddress(secureAddress, httpsPort)).syncUninterruptibly().channel());
                    http3BoundOnHttpsPort = true;
                    if (secureAddress == null) {
                        Logger.info("Listening for HTTP/3 on UDP port %s ...", httpsPort);
                    } else {
                        Logger.info("Listening for HTTP/3 at %2$s:%1$s  ...", httpsPort, secureAddress);
                    }
                } catch (ChannelException e) {
                    Logger.warn(e, "Could not bind QUIC on UDP port %s — HTTP/3 disabled, HTTPS+h2+h1.1 still active.", httpsPort);
                } catch (Exception e) {
                    Logger.warn(e, "Could not initialize HTTP/3 server — HTTPS+h2+h1.1 still active.");
                }
            } else {
                Throwable cause = Quic.unavailabilityCause();
                String arch = System.getProperty("os.arch");
                String os = System.getProperty("os.name");
                Logger.warn(cause, "Netty QUIC native is unavailable on %s/%s — HTTP/3 disabled, "
                        + "HTTPS+h2+h1.1 still active. PF-57 ships native-quic for "
                        + "osx-{aarch_64,x86_64}, linux-{aarch_64,x86_64}, windows-x86_64.",
                        os, arch);
            }
        }

        if (Play.mode == Mode.DEV || Play.runningInTestMode()) {
           // print this line to STDOUT - not using logger, so auto test runner will not block if logger is misconfigured (see #1222)
           System.out.println("~ Server is up and running");
        }
    }

    /**
     * Resolve worker EventLoop thread count from {@code play.netty.maxThreads}. The legacy
     * Netty 3 path honoured this property; the Netty 4 migration accidentally dropped it.
     * 0 / unset / unparseable values fall back to Netty's default (2 × cores).
     */
    private static int workerThreads() {
        String raw = Play.configuration.getProperty("play.netty.maxThreads");
        if (raw == null || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw.trim());
            return n < 0 ? 0 : n;
        } catch (NumberFormatException nfe) {
            Logger.warn("Invalid play.netty.maxThreads='%s'; using Netty default", raw);
            return 0;
        }
    }

    private static synchronized void registerForShutdown(EventLoopGroup... groups) {
        for (EventLoopGroup g : groups) {
            eventLoopGroups.add(g);
        }
        if (!shutdownHookRegistered) {
            // PF-119: prefer ordering the drain inside Play's standalone shutdown hook so the
            // server stops accepting + finishes in-flight requests BEFORE pluginCollection
            // .onApplicationStop() runs, instead of racing it from an independent, unordered hook.
            // registerShutdownDrain returns false when there is no Play shutdown hook to piggyback
            // on (embedded / non-standalone), in which case we keep our own JVM hook so the loops
            // still drain at exit.
            if (!Play.registerShutdownDrain(Server::gracefulShutdown)) {
                Runtime.getRuntime().addShutdownHook(new Thread(Server::gracefulShutdown, "play-netty-shutdown"));
            }
            shutdownHookRegistered = true;
        }
    }

    /**
     * Drain Netty IO threads. Called from the JVM shutdown hook; safe to call directly from
     * tests that want to release the groups without exiting the JVM. Bounded wait of 5s per
     * group so a hung handler can't hold up shutdown indefinitely.
     */
    public static synchronized void shutdownEventLoops() {
        for (EventLoopGroup g : eventLoopGroups) {
            try {
                g.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Logger.warn(t, "EventLoopGroup shutdown failed");
            }
        }
        eventLoopGroups.clear();
    }

    /**
     * PF-119 follow-up: close the listen sockets so the server stops accepting new connections,
     * while leaving the worker {@link EventLoopGroup}s (and already-accepted connections) alive so
     * in-flight requests can still complete and flush their responses. Bounded 2s wait per channel.
     */
    public static synchronized void stopAccepting() {
        for (Channel ch : serverChannels) {
            try {
                ch.close().await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Logger.warn(t, "Listen channel close failed");
            }
        }
        serverChannels.clear();
    }

    /**
     * PF-119 follow-up: graceful shutdown drain run by the standalone shutdown hook BEFORE
     * {@code pluginCollection.onApplicationStop()}. Three phases: (1) {@link #stopAccepting()}
     * halts new connections; (2) wait — bounded by {@code play.shutdown.drain.timeout} (default
     * 30s) — for in-flight request invocations to finish so their responses flush; (3)
     * {@link #shutdownEventLoops()} closes the event loops. The phase-2 wait is lock-free
     * ({@link Invoker#awaitQuiescence(long)} polls an atomic and holds no monitor), so it cannot
     * reintroduce the shutdown lock-order inversion this change set prevents.
     */
    public static void gracefulShutdown() {
        stopAccepting();
        long timeoutMs = drainTimeoutMs();
        long before = Invoker.inflightInvocations.get();
        if (before > 0) {
            if (Invoker.awaitQuiescence(timeoutMs)) {
                Logger.info("Shutdown drain: %d in-flight request(s) completed", before);
            } else {
                Logger.warn("Shutdown drain timed out after %d ms with %d request(s) still in flight; forcing close",
                        timeoutMs, Invoker.inflightInvocations.get());
            }
        }
        shutdownEventLoops();
    }

    private static long drainTimeoutMs() {
        try {
            return Long.parseLong(Play.configuration.getProperty("play.shutdown.drain.timeout", "30000"));
        } catch (NumberFormatException e) {
            return 30000;
        }
    }

    private String getOpt(String[] args, String arg, String defaultValue) {
        String s = "--" + arg + "=";
        for (String a : args) {
            if (a.startsWith(s)) {
                return a.substring(s.length());
            }
        }
        return defaultValue;
    }

    private static void writePID(File root) {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        File pidfile = new File(root, PID_FILE);
        if (pidfile.exists()) {
            throw new RuntimeException("The " + PID_FILE + " already exists. Is the server already running?");
        }
        IO.write(pid.getBytes(), pidfile);
    }

    public static void main(String[] args) throws Exception {
        try {
            File root = new File(System.getProperty("application.path", "."));
            if (System.getProperty("precompiled", "false").equals("true")) {
                Play.usePrecompiled = true;
            }
            if (System.getProperty("writepid", "false").equals("true")) {
                writePID(root);
            }

            // Allow the launcher to pin frameworkPath explicitly. Without
            // this, Play.init's auto-detect computes frameworkPath from
            // the play jar location (jar.getParentFile().getParentFile()),
            // which collapses to the application root when the bundle
            // ships the jar at <appRoot>/framework/play-X.jar — making
            // application.path == framework.path. The collision then
            // mis-tags every app file (including conf/routes) with the
            // {play} prefix in VirtualFile.relativePath() and breaks
            // precompiled-template lookup at startup with
            // NoSuchFileException on "from_play/conf/routes". Setting
            // it explicitly keeps the two paths distinct so the dev-time
            // and dist-time encodings agree.
            String frameworkPathProp = System.getProperty("framework.path");
            if (frameworkPathProp != null) {
                Play.frameworkPath = new File(frameworkPathProp).getAbsoluteFile();
            }

            Play.init(root, System.getProperty("play.id", ""));

            if (System.getProperty("precompile") == null) {
                new Server(args);
            } else {
                Logger.info("Done.");
            }
        }
        catch (Throwable e) {
            Logger.fatal(e, "Failed to start");
            System.exit(1);
        }
    }
}
