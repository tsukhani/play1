package play;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PF-175: with {@code java.util.logging.manager} set to log4j-jul's LogManager, which every Play
 * launcher now does, java.util.logging output reaches log4j2's appenders under its own logger
 * name, so a log4j2 Logger element can target it. This also guards the shipped jar pair.
 * log4j-jul must match log4j-core exactly, and log4j refuses a mismatched pair at runtime,
 * not at build time, so a bump that moves one without the other fails here instead of in
 * production.
 *
 * <p>JUL reads the property once, on first use, and this JVM's JUL was initialised long ago
 * (the JUnit platform logs through it). So the probe runs in a child JVM on this test
 * classpath, which is framework/lib. The launchers' side (that each one sets the property) is
 * covered by the gradle-plugin's JulBridgeArgsTest and BundleLauncherTest.
 */
public class JulBridgeTest {

    private static final String LOG4J_JUL_MANAGER = "org.apache.logging.log4j.jul.LogManager";

    /** Logs through java.util.logging only; anything tagged LOG4J2 on stdout is log4j2's doing. */
    public static final class Probe {
        public static void main(String[] args) {
            java.util.logging.Logger.getLogger("org.example.jul.Probe").warning("bridged through log4j-jul");
            java.util.logging.Logger.getLogger("org.example.jul.quiet").warning("dropped by its Logger element");
        }
    }

    @Test
    public void julRecordsReachLog4jAppendersUnderTheirOwnLoggerName(@TempDir File tmp) throws Exception {
        String out = runProbe(tmp, true);

        assertThat(out).contains("LOG4J2 org.example.jul.Probe WARN bridged through log4j-jul");
        // logger.quiet (level ERROR) names the JUL logger, so it applies to that logger's records.
        assertThat(out).doesNotContain("dropped by its Logger element");
    }

    @Test
    public void withoutTheManagerPropertyJulBypassesLog4j(@TempDir File tmp) throws Exception {
        // Mutation sanity: the property is what bridges, not merely log4j-jul being on the classpath.
        String out = runProbe(tmp, false);

        assertThat(out).contains("bridged through log4j-jul").doesNotContain("LOG4J2 ");
    }

    /** Run {@link Probe} in a child JVM and return its combined stdout and stderr. */
    private static String runProbe(File tmp, boolean bridged) throws Exception {
        File config = new File(tmp, "log4j2.properties");
        Files.writeString(config.toPath(), String.join("\n",
            "status = error",
            "appender.out.type = Console",
            "appender.out.name = OUT",
            "appender.out.layout.type = PatternLayout",
            "appender.out.layout.pattern = LOG4J2 %c %p %m%n",
            "logger.quiet.name = org.example.jul.quiet",
            "logger.quiet.level = ERROR",
            "rootLogger.level = INFO",
            "rootLogger.appenderRef.out.ref = OUT",
            ""), StandardCharsets.UTF_8);

        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dlog4j2.configurationFile=" + config.getAbsolutePath()));
        if (bridged) {
            cmd.add("-Djava.util.logging.manager=" + LOG4J_JUL_MANAGER);
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName()));

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("probe JVM exited").isTrue();
        assertThat(p.exitValue()).as("probe JVM exit code; output:%n%s", out).isZero();
        return out;
    }
}
