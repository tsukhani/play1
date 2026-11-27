package play.server;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.Quic;
import org.apache.logging.log4j.LogManager;
import play.Invoker;
import play.Logger;
import play.Play;
import play.Play.Mode;
import play.libs.IO;
import play.server.quic.Http3ServerInitializer;
import play.server.quic.Http3SslContextFactory;
import play.server.ssl.SslHttpServerPipelineFactory;
import play.utils.BootTimings;

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

    /**
     * Test-only hook (PF-188): run on the constructor's thread just before the HTTPS bind and
     * just before the HTTP/3 bind, with the listener's name ({@code "HTTPS"}, {@code "HTTP/3"}),
     * so a test can act inside a start: begin a shutdown there, or refuse the bind.
     */
    static Consumer<String> beforeBind;

    /**
     * PF-188: open while the constructor binds its listeners. Netty serves the HTTP channel as
     * soon as it is bound, so a SIGTERM can arrive with the HTTPS and HTTP/3 binds still to come
     * ({@code play aot-gen} sends one right after its first answered request). The drain waits
     * for this, at most {@link #BIND_WAIT_MS}, so the remaining binds complete on event loops it
     * has not yet terminated and every listener the start bound is closed with the rest.
     */
    private static volatile CountDownLatch binding = new CountDownLatch(0);

    /** How long the drain waits for the constructor to finish binding. Binding takes milliseconds. */
    static final long BIND_WAIT_MS = 5_000;

    public Server(String[] args) {
        binding = new CountDownLatch(1);
        try {
            listen(args);
        } finally {
            binding.countDown();
        }
    }

    private void listen(String[] args) {

        System.setProperty("file.encoding", "utf-8");
        Properties p = Play.configuration;

        httpPort = Integer.parseInt(getOpt(args, "http.port", p.getProperty("http.port", "-1")));
        httpsPort = Integer.parseInt(getOpt(args, "https.port", p.getProperty("https.port", "-1")));

        if (httpPort == -1 && httpsPort == -1) {
            httpPort = 9000;
        }

        // Port 0 asks the system for a free port, a different one for each listener (PF-230).
        if (httpPort == httpsPort && httpPort != 0) {
            Logger.error("Could not bind on https and http on the same port " + httpPort);
            fatal();
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
            fatal();
        }
        try {
            if (p.getProperty("https.address") != null) {
                secureAddress = InetAddress.getByName(p.getProperty("https.address"));
            } else if (System.getProperties().containsKey("https.address")) {
                secureAddress = InetAddress.getByName(System.getProperty("https.address"));
            }
        } catch (Exception e) {
            Logger.error(e, "Could not understand https.address");
            fatal();
        }

        try {
            if (httpPort != -1) {
                // Boss only accepts; one thread per bound port suffices. Worker count honours
                // play.netty.maxThreads if set (legacy Netty 3 toggle), otherwise two thirds of the
                // cores for plain HTTP, which never carries HTTP/2 (PF-255, see
                // defaultHttpWorkerThreads). 0 / unset / unparseable → default.
                EventLoopGroup bossGroup = new NioEventLoopGroup(1);
                EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads(true));
                registerForShutdown(bossGroup, workerGroup);
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new HttpServerPipelineFactory())
                        .childOption(ChannelOption.TCP_NODELAY, true);

                Channel channel = bind(bootstrap, new InetSocketAddress(address, httpPort));
                serverChannels.add(channel);
                httpPort = boundPort(channel, httpPort, "http.port");

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
            Logger.error("Could not bind on port %s: %s", httpPort, e.getMessage());
            fatal();
        }

        try {
            if (httpsPort != -1) {
                EventLoopGroup bossGroup = new NioEventLoopGroup(1);
                EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads(false));
                registerForShutdown(bossGroup, workerGroup);
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new SslHttpServerPipelineFactory())
                        .childOption(ChannelOption.TCP_NODELAY, true);

                if (beforeBind != null) beforeBind.accept("HTTPS");
                Channel channel = bind(bootstrap, new InetSocketAddress(secureAddress, httpsPort));
                serverChannels.add(channel);
                httpsPort = boundPort(channel, httpsPort, "https.port");

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
            Logger.error("Could not bind on port %s: %s", httpsPort, e.getMessage());
            fatal();
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
                    if (beforeBind != null) beforeBind.accept("HTTP/3");
                    serverChannels.add(bind(bootstrap, new InetSocketAddress(secureAddress, httpsPort)));
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
     * Bind and wait, failing with a {@link ChannelException} the callers catch. A refused bind
     * fails the future with the OS's {@code java.net.BindException}, which
     * {@code syncUninterruptibly()} rethrows as it is: a checked exception that no catch here
     * named, so a port in use left the start through {@code Server.main} ("Failed to start",
     * exit 1) instead of {@link #fatal()}.
     */
    private static Channel bind(AbstractBootstrap<?, ?> bootstrap, InetSocketAddress address) {
        ChannelFuture bound = bootstrap.bind(address).awaitUninterruptibly();
        if (!bound.isSuccess()) {
            throw new ChannelException(String.valueOf(bound.cause()), bound.cause());
        }
        return bound.channel();
    }

    /**
     * PF-230: the port a listener got. For port 0, the one the system picked, which then also
     * becomes the configuration's value of {@code key}, where an application reads its port, and
     * stays it through every later read of the configuration ({@link Play#boundPorts}). The JUnit
     * Platform runner starts the server of each test JVM that way, so that the JVMs of one run
     * never share a port, whatever the configuration names.
     */
    private static int boundPort(Channel channel, int requested, String key) {
        if (requested != 0) {
            return requested;
        }
        String port = String.valueOf(((InetSocketAddress) channel.localAddress()).getPort());
        Play.boundPorts.put(key, port);
        Play.configuration.setProperty(key, port);
        return Integer.parseInt(port);
    }

    /**
     * PF-188: on a standalone server {@link Play#fatalServerErrorOccurred()} exits the JVM from
     * inside the constructor, whose {@code finally} is then never reached: the exit runs the drain
     * and waits for it. So the binds are declared over first, or a failed start would wait out
     * {@link #BIND_WAIT_MS} before it exits.
     */
    private static void fatal() {
        binding.countDown();
        Play.fatalServerErrorOccurred();
    }

    /**
     * Resolve worker EventLoop thread count from {@code play.netty.maxThreads}. The legacy
     * Netty 3 path honoured this property; the Netty 4 migration accidentally dropped it.
     * 0 / unset / unparseable values fall back to {@link #defaultWorkerThreads(int)}.
     */
    private static int workerThreads(boolean plainHttp) {
        return workerThreads(Play.configuration.getProperty("play.netty.maxThreads"),
                Runtime.getRuntime().availableProcessors(), plainHttp);
    }

    /** The HTTPS listener's count: {@link #workerThreads(String, int, boolean)} for TLS. */
    static int workerThreads(String raw, int cores) {
        return workerThreads(raw, cores, false);
    }

    static int workerThreads(String raw, int cores, boolean plainHttp) {
        int fallback = plainHttp ? defaultHttpWorkerThreads(cores) : defaultWorkerThreads(cores);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int n = Integer.parseInt(raw.trim());
            return n <= 0 ? fallback : n;
        } catch (NumberFormatException nfe) {
            Logger.warn("Invalid play.netty.maxThreads='%s'; using the default", raw);
            return fallback;
        }
    }

    /**
     * PF-255: the plain-HTTP listener's default, two thirds of the cores, rounded up and at least 2.
     * Plain HTTP never carries HTTP/2 (there is no h2c), and it is what an application behind an
     * HTTP/1.1 proxy receives. On a server whose 12 CPUs nothing else shares, 8 loops served 10 to 19%
     * more cheap requests over HTTP/1.1 than 6, at a lower 99th percentile on plain text and 0.62 ms
     * against 0.43 on JDBC, and an action doing real CPU work (200 rows to JSON) as well as 6 (76.9k
     * against 75.2k requests/s, p99 2.22 ms against 2.14). The 8 loops' cost came only over HTTP/2, at
     * 16 streams in flight per connection with that action, a p99 of 246 ms against 30: loops and busy
     * carriers time-sliced on the CPUs, every stream of a waiting loop stalled. So HTTPS, which serves
     * HTTP/2, keeps half the cores ({@link #defaultWorkerThreads}). With both listeners under heavy
     * load at once a server runs more loops than it has cores. The larger count costs a connection that
     * carries one request, as from a proxy without upstream keep-alive: 82 to 83 µs of CPU per request
     * with 8 loops against 73.6 with 6. {@code -Dio.netty.eventLoopThreads} still wins when it is given.
     */
    static int defaultHttpWorkerThreads(int cores) {
        if (System.getProperty("io.netty.eventLoopThreads") != null) return 0;
        return Math.max(2, (2 * cores + 2) / 3);
    }

    /**
     * Half the cores, rounded up and at least 2 (PF-244), where Netty's own default is twice the cores.
     * The event loops only decode, encode and write: every action runs on a virtual thread of its
     * own. Each loop is a platform thread with its own stack and its own magazines in Netty's
     * adaptive allocator, every one of them holding direct chunks of at least 128 KiB that are
     * malloc'd outside the JVM's native memory tracking. On 14 cores the 28 loops held 17 to 26 MB
     * more than 4 did and served fewer requests, since a loop with two connections is woken for
     * nearly every write (PF-240, which made a quarter of the cores the default). On a server whose
     * CPUs nothing else shares, 12 of them, a quarter was the limit: 3 loops kept the server at 4 to 5
     * of its CPUs and served 182k plain-text requests/s at p99 0.72 ms, where 6 served 305k at 0.43
     * ms. Never fewer than 2: a JVM sized for 4 cores ran its lone loop at 98 to 100% of a core
     * under the Helidon harness's load and served fewer requests, at a higher p99, than Netty's 8
     * did, where 2 served more than either. {@code -Dio.netty.eventLoopThreads}, Netty's own
     * setting, still wins when it is given: 0 hands the choice to Netty, which reads it.
     */
    static int defaultWorkerThreads(int cores) {
        if (System.getProperty("io.netty.eventLoopThreads") != null) return 0;
        return Math.max(2, (cores + 1) / 2);
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
     * <p>
     * PF-188: before all three it waits, at most {@link #BIND_WAIT_MS}, for a constructor still
     * binding. That wait holds no monitor either: the constructor takes this class's to register
     * its event loops.
     */
    public static void gracefulShutdown() {
        try {
            if (!binding.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Logger.warn("Shutdown drain stopped waiting for the server to finish binding after %d ms;"
                        + " closing the listeners bound so far", BIND_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** The first argument with this prefix wins. Play.extractHttpPort reads --http.port alike (PF-224): change the two together. */
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

    /**
     * PF-239: classes whose static initializers every start runs on its main thread, and which
     * depend on nothing of Play's.
     * <ul>
     * <li>The Netty classes the bind and the first request initialize, in the order they do.
     * Their initializers read the platform (Unsafe, the direct memory limit, the selector
     * provider, the network interfaces for a channel id). The handlers are the Netty ones of the
     * default {@code play.netty.pipeline} ({@link HttpServerPipelineFactory#DEFAULT_PIPELINE})
     * and no others: initializing {@code HttpContentCompressor}, which that pipeline leaves out,
     * extracted and loaded brotli4j's native library at every start, which PF-173 keeps off a
     * deployment that does not compress, and the main thread stalled behind the load for up to
     * 100 ms.</li>
     * <li>{@code jregex.CharacterClass}, whose initializer and {@code jregex.Bitset}'s build
     * jregex's Unicode tables when Router's first pattern is compiled: 12 to 14 ms of every
     * start with an AOT cache, which cannot hold jregex's Java 1.1 classes.</li>
     * </ul>
     */
    static final String[] PREINIT = {
        "io.netty.util.internal.PlatformDependent",
        "io.netty.util.NetUtil",
        "io.netty.channel.DefaultChannelId",
        "io.netty.buffer.ByteBufUtil",
        "io.netty.buffer.ByteBufAllocator",
        "io.netty.channel.nio.NioIoHandler",
        "io.netty.channel.nio.NioEventLoopGroup",
        "io.netty.channel.socket.nio.NioServerSocketChannel",
        "io.netty.channel.socket.nio.NioSocketChannel",
        "io.netty.channel.DefaultChannelPipeline",
        "io.netty.handler.codec.http.HttpRequestDecoder",
        "io.netty.handler.codec.http.HttpResponseEncoder",
        "io.netty.handler.stream.ChunkedWriteHandler",
        "io.netty.handler.codec.http.DefaultFullHttpResponse",
        "io.netty.handler.codec.http.HttpHeaderNames",
        "io.netty.handler.codec.http.HttpResponseStatus",
        "jregex.CharacterClass",
    };

    /**
     * PF-239: initialize {@link #PREINIT}, open and close a selector and a server socket
     * channel, which sets up the JDK's NIO natives and socket options the bind needs, and
     * allocate and free one direct buffer, which links Netty's native memory calls, on a daemon
     * thread while the application starts. The bind and the first request did this on their own
     * thread: 25 to 50 ms of the bind phase on the head-to-head's application, on a thread that
     * is otherwise idle until then. A class initialized here is the one the server then uses;
     * one that fails to initialize fails again, and is reported, where the server first uses it.
     */
    static Thread preinitialize() {
        Thread thread = new Thread(() -> {
            ClassLoader loader = Server.class.getClassLoader();
            for (String name : PREINIT) {
                try {
                    Class.forName(name, true, loader);
                } catch (Throwable ignored) {
                    // the server reports it when it gets there
                }
            }
            try (Selector selector = Selector.open(); ServerSocketChannel channel = ServerSocketChannel.open()) {
                // opened only to set the classes up
            } catch (Throwable ignored) {
                // as above
            }
            try {
                io.netty.buffer.Unpooled.directBuffer(1).release();
            } catch (Throwable ignored) {
                // as above
            }
        }, "play-preinit");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public static void main(String[] args) throws Exception {
        // PF-189: every launcher turns log4j's own shutdown hook off, and Play.shutdownHook,
        // which Play.start() registers, stops log4j once the application has stopped. A JVM
        // that ends before any start -- play precompile, a start that fails in Play.init, a
        // DEV server stopped before its first request -- has only this hook, which stops
        // log4j so that appenders that buffer still write what they hold.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!Play.shutdownHookEnabled()) {
                LogManager.shutdown();
            }
        }, "play-log4j-shutdown"));
        // PF-243: the stop a Windows launcher sends, where no signal reaches the JVM
        StopFile.watchFromProperty();
        if (System.getProperty("precompile") == null) {
            preinitialize();
        }
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
                BootTimings.time(BootTimings.Phase.BIND, () -> new Server(args));
                // PF-178: DEV has not started the application yet; Play.start() logs it, on the first request
                if (Play.mode == Mode.PROD) {
                    BootTimings.logBoot();
                }
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
