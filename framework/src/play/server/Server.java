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

    public static final String PID_FILE = "server.pid";

    /**
     * Tracks every {@link EventLoopGroup} created during bind so the JVM shutdown hook can
     * gracefully drain accept/IO threads. Without this, the daemon NIO threads outlive the
     * application — harmless on real JVM exit but a real leak in DEV hot-reload restarts and
     * test harnesses that reinstantiate {@link Server} repeatedly within one JVM.
     */
    private static final List<EventLoopGroup> eventLoopGroups = new ArrayList<>();
    private static volatile boolean shutdownHookRegistered = false;

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

                bootstrap.bind(new InetSocketAddress(address, httpPort)).syncUninterruptibly();

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

                bootstrap.bind(new InetSocketAddress(secureAddress, httpsPort)).syncUninterruptibly();

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

        // PF-57: HTTP/3 over QUIC. Opt-in via play.http3.enabled. Binds a UDP listener
        // alongside the existing TCP HTTP/HTTPS listeners. UDP port defaults to the HTTPS
        // port so a single externally-visible port number serves both TCP+TLS (h1.1/h2)
        // and UDP+QUIC (h3) — the standard h3 deployment shape.
        boolean http3Enabled = Boolean.parseBoolean(Play.configuration.getProperty("play.http3.enabled", "false"));
        if (http3Enabled) {
            if (httpsPort == -1) {
                Logger.error("play.http3.enabled=true requires https.port to be set (HTTP/3 has no plaintext mode)");
                Play.fatalServerErrorOccurred();
            }
            // Quic.isAvailable() returns false on platforms without a published native-quic
            // artifact (e.g. linux-riscv64) or when the JNI loader couldn't link the binary.
            // Fail loudly with the underlying cause so operators see exactly what's missing.
            if (!Quic.isAvailable()) {
                Throwable cause = Quic.unavailabilityCause();
                String arch = System.getProperty("os.arch");
                String os = System.getProperty("os.name");
                Logger.error(cause, "play.http3.enabled=true but Netty QUIC native is unavailable on %s/%s. "
                        + "PF-57 ships native-quic for osx-{aarch_64,x86_64}, linux-{aarch_64,x86_64}, "
                        + "windows-x86_64; other platforms (e.g. linux-riscv64) must run with play.http3.enabled=false.",
                        os, arch);
                Play.fatalServerErrorOccurred();
            }
            int http3Port;
            try {
                http3Port = Integer.parseInt(Play.configuration.getProperty("play.http3.port", String.valueOf(httpsPort)));
            } catch (NumberFormatException nfe) {
                Logger.error(nfe, "Invalid play.http3.port; falling back to https.port=%s", httpsPort);
                http3Port = httpsPort;
            }
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
                bootstrap.bind(new InetSocketAddress(secureAddress, http3Port)).syncUninterruptibly();
                if (secureAddress == null) {
                    Logger.info("Listening for HTTP/3 on UDP port %s ...", http3Port);
                } else {
                    Logger.info("Listening for HTTP/3 at %2$s:%1$s  ...", http3Port, secureAddress);
                }
            } catch (ChannelException e) {
                Logger.error("Could not bind QUIC on UDP port " + http3Port, e);
                Play.fatalServerErrorOccurred();
            } catch (Exception e) {
                Logger.error(e, "Could not initialize HTTP/3 server");
                Play.fatalServerErrorOccurred();
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
            Runtime.getRuntime().addShutdownHook(new Thread(Server::shutdownEventLoops, "play-netty-shutdown"));
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
