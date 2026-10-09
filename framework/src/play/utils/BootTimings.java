package play.utils;

import java.lang.management.ManagementFactory;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import play.Logger;

/**
 * PF-178: where one application start spent its time, logged as a single INFO line:
 *
 * <pre>Started in 1873 ms: jvm=312 conf=14 logging=180 modules=2 plugins=96 classes=81 templates=- routes=7
 * onApplicationStart=1023 onApplicationStart.play.db.jpa.JPAPlugin=987 afterApplicationStart=35 bind=74 other=49</pre>
 *
 * The framework charges its boot phases to the current start through the static methods; an
 * instance holds one start's figures. The marks sit at the call sites in {@code Play},
 * {@code PluginCollection} and {@code Server}, never inside the method they time, so no phase
 * runs inside another and what is left over for {@code other} cannot go negative.
 *
 * <p>What a start is depends on the mode. In PROD the application starts once, inside
 * {@code Play.init}, and the server binds afterwards: {@code Server.main} logs the line once the
 * server is listening, and the total counts from JVM start ({@link #logBoot()}). In DEV
 * {@code Play.start()} waits for the first request and runs again on every reload, any time
 * after the JVM came up, so each call begins a start of its own and logs it
 * ({@link #logStart()}); the phases of {@code Play.init} and the bind are outside it.
 */
public final class BootTimings {

    /** The phases of a start, in the order they run and are reported. */
    public enum Phase {
        /** JVM start to {@code Play.init}. Known only when the total counts from JVM start. */
        JVM("jvm"),
        /** {@code Play.readConfiguration()}, which PROD runs in {@code Play.init} and again in {@code Play.start()}. */
        CONF("conf"),
        /** {@code Logger.init()}, where log4j normally comes up. */
        LOGGING("logging"),
        MODULES("modules"),
        /** {@code PluginCollection.loadPlugins()}: instantiating the plugins and their {@code onLoad}. */
        PLUGINS("plugins"),
        /** {@code ApplicationClassloader.getAllClasses()}: the precompiled scan, or compiling and enhancing {@code app/}. */
        CLASSES("classes"),
        /** Compiling every template, which only a PROD start that is not precompiled does. */
        TEMPLATES("templates"),
        ROUTES("routes"),
        ON_APPLICATION_START("onApplicationStart"),
        AFTER_APPLICATION_START("afterApplicationStart"),
        BIND("bind");

        private final String key;

        Phase(String key) {
            this.key = key;
        }
    }

    private static volatile BootTimings current = new BootTimings();

    private final long begin = System.nanoTime();
    /** No entry for a phase that never ran, which is reported differently from one that took no time. */
    private final Map<Phase, Long> phases = new EnumMap<>(Phase.class);
    private final Map<String, Long> onApplicationStart = new LinkedHashMap<>();

    BootTimings() {
    }

    /** Begin timing a new start: {@code Play.init}, and in DEV every {@code Play.start()}. */
    public static BootTimings begin() {
        BootTimings timings = new BootTimings();
        current = timings;
        return timings;
    }

    /**
     * Run {@code block} and charge its duration to {@code phase}. The start is picked before
     * the block runs: in DEV a first request can begin a new one while the server is still
     * binding its other ports, and that bind is no part of it.
     */
    public static void time(Phase phase, Runnable block) {
        BootTimings timings = current;
        long start = System.nanoTime();
        try {
            block.run();
        } finally {
            timings.record(phase, System.nanoTime() - start);
        }
    }

    /** Run one plugin's {@code onApplicationStart} and charge its duration to that plugin, named by its class. */
    public static void timeOnApplicationStart(String plugin, Runnable hook) {
        BootTimings timings = current;
        long start = System.nanoTime();
        try {
            hook.run();
        } finally {
            timings.recordOnApplicationStart(plugin, System.nanoTime() - start);
        }
    }

    /** DEV: log the start that just completed. Its total is the time since {@link #begin()}. */
    public static void logStart() {
        BootTimings timings = current;
        Logger.info("%s", timings.format(System.nanoTime() - timings.begin));
    }

    /**
     * PROD: log the boot now that the server is listening. Its total is the JVM's uptime, so it
     * includes what no mark in the framework can see: the JVM coming up and loading the framework.
     * A runtime image built without the java.management module cannot say how long it has been
     * up: the total then counts from {@code Play.init}, and {@code jvm} is not reported.
     */
    public static void logBoot() {
        BootTimings timings = current;
        long listening = System.nanoTime();
        long total = listening - timings.begin;
        try {
            // The first use of the runtime MXBean costs milliseconds itself: take them back out
            long uptime = TimeUnit.MILLISECONDS.toNanos(ManagementFactory.getRuntimeMXBean().getUptime())
                    - (System.nanoTime() - listening);
            timings.record(Phase.JVM, uptime - total);
            total = uptime;
        } catch (LinkageError noManagementModule) {
            // Server.main exits on anything thrown here, and the server is already listening
        }
        Logger.info("%s", timings.format(total));
    }

    synchronized void record(Phase phase, long nanos) {
        phases.merge(phase, nanos, Long::sum);
    }

    synchronized void recordOnApplicationStart(String plugin, long nanos) {
        onApplicationStart.merge(plugin, nanos, Long::sum);
    }

    /**
     * The line: the total, then every phase as {@code name=milliseconds} with {@code -} for one
     * that never ran, each plugin's {@code onApplicationStart} that does not round to zero after
     * that phase, and last {@code other}, the part of the total no phase accounts for.
     */
    synchronized String format(long totalNanos) {
        StringBuilder line = new StringBuilder("Started in ").append(millis(totalNanos)).append(" ms:");
        long other = totalNanos;
        for (Phase phase : Phase.values()) {
            Long nanos = phases.get(phase);
            line.append(' ').append(phase.key).append('=');
            if (nanos == null) {
                line.append('-');
            } else {
                line.append(millis(nanos));
                other -= nanos;
            }
            if (phase == Phase.ON_APPLICATION_START) {
                onApplicationStart.forEach((plugin, pluginNanos) -> {
                    // most plugins do nothing here; naming them all would bury the ones that matter
                    if (millis(pluginNanos) > 0) {
                        line.append(' ').append(phase.key).append('.').append(plugin).append('=').append(millis(pluginNanos));
                    }
                });
            }
        }
        return line.append(" other=").append(millis(other)).toString();
    }

    private static long millis(long nanos) {
        return (nanos + 500_000) / 1_000_000;
    }
}
