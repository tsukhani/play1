package integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.classloading.ApplicationClassloaderState;
import play.server.Server;

/**
 * PF-186: every {@code play run} but the first starts with the application's classes already in
 * {@code tmp/bytecode}, and such a start failed in every package that has a {@code package-info}:
 * {@code IllegalArgumentException} from {@code ClassLoader.definePackage}, a 500 for the request
 * that started the application. Once that was fixed, the request after such a start took every
 * package-info for edited, recompiled it and emptied the application's caches. The start that
 * compiles from source shows neither, so each case boots the same scratch application twice, in a
 * child JVM each time, as two {@code play run}s do.
 */
public class PackageInfoBytecodeCacheTest {

    /** Packages with a package-info, named after the ones the failure was first seen in. */
    private static final List<String> PACKAGES = List.of("services.tts", "services.grapheval", "models", "jobs");

    /** Classes in each package. Which of a package's classes is loaded first decides whether it fails. */
    private static final int CLASSES_PER_PACKAGE = 10;

    private static final String ANNOTATION = "annotations.Marked";

    private static final String NOTHING_CHANGED = "next request found changed classes: false";

    private static final String EDIT_FOUND = "request after an edit found changed classes: true";

    /**
     * Boots in DEV, starts the application as its first request does and reports each package's
     * annotation, then looks for changes as every later request does, before and after an edit.
     */
    public static final class Probe {
        @SuppressWarnings("unchecked")
        public static void main(String[] args) throws Exception {
            Server.main(args);
            try {
                Play.start();
                Class<? extends Annotation> marked = (Class<? extends Annotation>) Play.classloader.loadClass(ANNOTATION);
                for (String name : PACKAGES) {
                    Package pkg = Play.classloader.loadClass(name + ".C00").getPackage();
                    System.out.println("annotation of " + name + ": " + pkg.getAnnotation(marked));
                }
                // a change found here recompiles the class and empties the application's caches, or,
                // with no HotSwap agent as in this JVM, restarts the application; either way the state moves on
                ApplicationClassloaderState state = Play.classloader.currentState;
                Play.detectChanges();
                System.out.println("next request found changed classes: " + !Play.classloader.currentState.equals(state));

                // the recompile caches the edited source, so a start after this one still starts from the cache
                File edited = new File(Play.applicationPath, "app/services/tts/package-info.java");
                long before = edited.lastModified();
                Files.writeString(edited.toPath(), "// edited\n" + Files.readString(edited.toPath()));
                edited.setLastModified(before + 1000);
                state = Play.classloader.currentState;
                Play.detectChanges();
                System.out.println("request after an edit found changed classes: " + !Play.classloader.currentState.equals(state));
            } catch (Throwable t) {
                // the server's threads would keep the JVM up until the test gives up on it
                t.printStackTrace();
                System.exit(1);
            }
            System.exit(0);
        }
    }

    @Test
    public void secondDevStartFromTheBytecodeCacheServesAndKeepsThePackageAnnotations(@TempDir File app) throws Exception {
        scratchApp(app);

        List<String> first = boot(app, "first start");
        assertThat(new File(app, "tmp/bytecode/DEV").list()).as("the first start fills the cache").isNotEmpty();
        assertPackagesAnnotated(first);

        List<String> second = boot(app, "start from the cache");
        assertPackagesAnnotated(second);
    }

    @Test
    public void requestAfterAStartFromTheBytecodeCacheReloadsOnlyWhatWasEdited(@TempDir File app) throws Exception {
        scratchApp(app);

        List<String> first = boot(app, "first start");
        assertThat(first).as("console:%n%s", String.join("\n", first)).contains(NOTHING_CHANGED, EDIT_FOUND);

        // nothing compiled a package-info read from the cache, so nothing recorded when its file was last changed
        List<String> second = boot(app, "start from the cache");
        assertThat(second).as("console:%n%s", String.join("\n", second)).contains(NOTHING_CHANGED, EDIT_FOUND);
    }

    private static void assertPackagesAnnotated(List<String> console) {
        for (String name : PACKAGES) {
            assertThat(console).as("console:%n%s", String.join("\n", console))
                    .contains("annotation of " + name + ": @" + ANNOTATION + "()");
        }
    }

    private static void scratchApp(File app) throws IOException {
        write(app, "conf/application.conf", "application.name=package-info-cache\napplication.secret=${PLAY_SECRET}\n"
                + "application.mode=dev\n");
        write(app, "conf/routes", "GET / Application.index\n");
        write(app, "app/controllers/Application.java", "package controllers;\n"
                + "public class Application extends play.mvc.Controller {\n"
                + "    public static void index() { renderText(\"ok\"); }\n"
                + "}\n");
        // an application's own package annotation, as jspecify's @NullMarked is in the application that showed this
        write(app, "app/annotations/Marked.java", "package annotations;\n"
                + "import java.lang.annotation.*;\n"
                + "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PACKAGE)\n"
                + "public @interface Marked { }\n");
        for (String name : PACKAGES) {
            String dir = "app/" + name.replace('.', '/') + "/";
            write(app, dir + "package-info.java", "@annotations.Marked\npackage " + name + ";\n");
            for (int i = 0; i < CLASSES_PER_PACKAGE; i++) {
                String simple = String.format("C%02d", i);
                write(app, dir + simple + ".java", "package " + name + ";\npublic class " + simple + " { }\n");
            }
        }
    }

    private static void write(File app, String path, String content) throws IOException {
        Path file = app.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Run {@link Probe} on {@code app} in a child JVM and return its console, stdout and stderr together. */
    private static List<String> boot(File app, String which) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-DPLAY_SECRET=packageinfocachetestsecretkey1234567890packageinfocachetestsecret",
            "-Dapplication.path=" + app.getAbsolutePath(),
            // port 0: the kernel picks a free one, so this never collides with the suite's fixed ports
            "-cp", System.getProperty("java.class.path"), Probe.class.getName(), "--http.port=0"));

        File console = new File(app, "console.log");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(console).start();
        boolean exited = p.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly().waitFor();
        }
        String out = Files.readString(console.toPath(), StandardCharsets.UTF_8);
        assertThat(exited).as("%s: probe JVM exited; console:%n%s", which, out).isTrue();
        assertThat(p.exitValue()).as("%s: probe JVM exit code; console:%n%s", which, out).isZero();
        return out.lines().toList();
    }
}
