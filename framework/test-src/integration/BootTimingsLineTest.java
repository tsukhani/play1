package integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.server.Server;

/**
 * PF-178: every application start logs one {@code Started in N ms: phase=ms ...} line. Where it
 * is logged and what its total covers differ by mode, and both depend on the real entry point:
 * in PROD the line comes from {@code Server.main} after the bind and counts from JVM start. So
 * each case boots a scratch application in a child JVM and reads its console. This suite's own
 * Play is one shared DEV instance that cannot be restarted under the other tests.
 */
public class BootTimingsLineTest {

    private static final String LINE = "Started in ";

    /** Enough classes that defining them takes whole milliseconds, so a missing mark shows as 0. */
    private static final int MODEL_CLASSES = 200;

    /**
     * A precompiled boot: everything is timed but the template compilation it has no need to do.
     * {@code other} is what the phases leave of the total, and only matches while that is not
     * negative, which is to say while no stretch of the boot is counted twice.
     */
    private static final Pattern PROD_LINE = prodLine("(?<jvm>\\d+)", "-");

    /** The same boot of an application nobody precompiled, which compiles its templates on the way. */
    private static final Pattern COMPILING_PROD_LINE = prodLine("(?<jvm>\\d+)", "\\d+");

    /** And that boot where the JVM cannot say how long it has been up: nothing is known of the time before Play.init. */
    private static final Pattern NO_UPTIME_PROD_LINE = prodLine("-", "\\d+");

    private static Pattern prodLine(String jvm, String templates) {
        return Pattern.compile("Started in (\\d+) ms: jvm=" + jvm + " conf=(\\d+) logging=(\\d+)"
                + " modules=(\\d+) plugins=(\\d+) classes=(?<classes>\\d+) templates=" + templates + " routes=(\\d+)"
                + " onApplicationStart=(\\d+)(?: onApplicationStart\\.\\S+=\\d+)* afterApplicationStart=(\\d+) bind=(\\d+)"
                + " other=(\\d+)$");
    }

    private static final Pattern DEV_LINE = Pattern.compile("Started in (\\d+) ms: jvm=- conf=(\\d+) logging=- modules=-"
            + " plugins=- classes=(\\d+) templates=- routes=(\\d+)"
            + " onApplicationStart=(\\d+)(?: onApplicationStart\\.\\S+=\\d+)* afterApplicationStart=(\\d+) bind=-"
            + " other=(\\d+)$");

    /** Boots through the real entry point, then stands in for the requests that start a DEV application. */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Server.main(args);
            for (int i = 0; i < Integer.getInteger("probe.starts", 0); i++) {
                Play.start();
            }
            System.exit(0);
        }
    }

    @Test
    public void precompiledProdBootLogsOneLineAfterTheBindWithEveryPhaseNamed(@TempDir File app) throws Exception {
        scratchApp(app, "application.mode=dev");

        // play precompile: Play.init returns before the application starts, so there is nothing to report
        List<String> precompile = boot(app, 0, "-Dprecompile=yes");
        assertThat(precompile).noneMatch(line -> line.contains(LINE));
        assertThat(new File(app, "precompiled/java/models/M000.class")).exists();

        // what a bundle runs: the classes come from precompiled/java, a path no timer covered before
        List<String> boot = boot(app, 0, "-Dprecompiled=true");
        List<String> started = boot.stream().filter(line -> line.contains(LINE)).toList();
        assertThat(started).as("console:%n%s", String.join("\n", boot)).hasSize(1);
        assertThat(boot.indexOf(started.get(0))).as("logged once the server is listening")
                .isGreaterThan(indexOfLineContaining(boot, "Listening for HTTP"));

        Matcher m = PROD_LINE.matcher(started.get(0));
        assertThat(m.find()).as("every phase named, and timed but for the templates, in: %s", started.get(0)).isTrue();
        assertThat(Long.parseLong(m.group("jvm"))).as("jvm: the total counts from JVM start").isPositive();
        assertThat(Long.parseLong(m.group("classes"))).as("classes: the precompiled scan is timed").isPositive();
    }

    @Test
    public void precompiledProdBootNeverLoadsTheJavaCompiler(@TempDir File app) throws Exception {
        scratchApp(app, "application.mode=dev");
        boot(app, 0, "-Dprecompile=yes");

        // The JDT classes come out of a signed jar. Opening it, to build a compiler that a
        // precompiled start never runs, took about 50 ms of every such start, unreported by any phase.
        List<String> boot = boot(app, 0, "-Dprecompiled=true", "-Xlog:class+load");
        assertThat(boot).as("the application started").anyMatch(line -> line.contains(LINE));
        assertThat(boot.stream().filter(line -> line.contains(" org.eclipse.jdt.")).limit(5).toList())
                .as("JDT classes loaded by a start that compiles nothing").isEmpty();
    }

    @Test
    public void prodBootThatCompilesTimesTheCompilationOfClassesAndTemplates(@TempDir File app) throws Exception {
        // play start on an application nobody precompiled: Play.preCompile() does it during the boot,
        // through marks of its own that the precompiled boot never reaches
        scratchApp(app, "application.mode=prod");

        List<String> boot = boot(app, 0);
        List<String> started = boot.stream().filter(line -> line.contains(LINE)).toList();
        assertThat(started).as("console:%n%s", String.join("\n", boot)).hasSize(1);

        Matcher m = COMPILING_PROD_LINE.matcher(started.get(0));
        assertThat(m.find()).as("every phase timed, the templates too, in: %s", started.get(0)).isTrue();
        assertThat(Long.parseLong(m.group("classes"))).as("classes: compiling them is timed").isPositive();
    }

    @Test
    public void prodBootOnARuntimeWithoutJavaManagementStartsAndTimesItselfFromPlayInit(@TempDir File app) throws Exception {
        // a jlink image need not carry java.management, which is where the JVM's uptime comes from;
        // with the metrics off nothing else in this boot asks for it, and the line must not be what stops it
        scratchApp(app, "application.mode=prod", "metrics.enabled=false");

        List<String> boot = boot(app, 0, "--limit-modules=" + modulesThatDoNotNeedJavaManagement());
        List<String> started = boot.stream().filter(line -> line.contains(LINE)).toList();
        assertThat(started).as("console:%n%s", String.join("\n", boot)).hasSize(1);
        assertThat(NO_UPTIME_PROD_LINE.matcher(started.get(0)).find())
                .as("timed from Play.init, with no figure for the JVM, in: %s", started.get(0)).isTrue();
    }

    @Test
    public void devLogsOneLinePerStartCoveringThatStartAlone(@TempDir File app) throws Exception {
        scratchApp(app, "application.mode=dev");

        // the first start, then what a reload does
        List<String> boot = boot(app, 2);
        List<String> started = boot.stream().filter(line -> line.contains(LINE)).toList();
        assertThat(started).as("console:%n%s", String.join("\n", boot)).hasSize(2);
        assertThat(started).allSatisfy(line -> assertThat(DEV_LINE.matcher(line).find())
                .as("the phases of Play.init and the bind are outside a DEV start: %s", line).isTrue());
    }

    private static void scratchApp(File app, String... conf) throws IOException {
        write(app, "conf/application.conf", "application.name=boot-timings\napplication.secret=${PLAY_SECRET}\n"
                + String.join("\n", conf) + "\n");
        write(app, "conf/routes", "GET / Application.index\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() { renderText(\"ok\"); }\n"
                + "}\n");
        for (int i = 0; i < MODEL_CLASSES; i++) {
            String name = String.format("M%03d", i);
            write(app, "app/models/" + name + ".java", "package models;\npublic class " + name + " { }\n");
        }
    }

    private static void write(File app, String path, String content) throws IOException {
        Path file = app.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Run {@link Probe} on {@code app} in a child JVM and return its console, stdout and stderr together. */
    private static List<String> boot(File app, int devStarts, String... jvmArgs) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-DPLAY_SECRET=boottimingstestsecretkey1234567890boottimingstestsecretkey123456",
            "-Dapplication.path=" + app.getAbsolutePath(),
            "-Dprobe.starts=" + devStarts));
        cmd.addAll(List.of(jvmArgs));
        // port 0: the kernel picks a free one, so this never collides with the suite's fixed ports
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), "--http.port=0"));

        File console = new File(app, "console.log");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(console).start();
        boolean exited = p.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly().waitFor();
        }
        String out = Files.readString(console.toPath(), StandardCharsets.UTF_8);
        assertThat(exited).as("probe JVM exited; console:%n%s", out).isTrue();
        assertThat(p.exitValue()).as("probe JVM exit code; console:%n%s", out).isZero();
        return out.lines().toList();
    }

    /** Every module of this JDK but java.management and the ones that require it, as {@code --limit-modules} wants them. */
    private static String modulesThatDoNotNeedJavaManagement() {
        Set<ModuleReference> jdk = ModuleFinder.ofSystem().findAll();
        Set<String> excluded = new HashSet<>(Set.of("java.management"));
        // to a fixed point: a module that requires an excluded one would bring it back
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ModuleReference module : jdk) {
                if (module.descriptor().requires().stream().anyMatch(required -> excluded.contains(required.name()))) {
                    grew |= excluded.add(module.descriptor().name());
                }
            }
        }
        return jdk.stream().map(module -> module.descriptor().name()).filter(name -> !excluded.contains(name)).sorted()
                .collect(Collectors.joining(","));
    }

    private static int indexOfLineContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
