package play;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the directory handling in the repo-root {@code play} shim's {@code find_gradle}.
 *
 * <p>The shim picks a runner in order: {@code ./gradlew} in the working directory, then
 * {@code $PLAY_HOME/gradlew} when standing in the framework itself, then a system
 * {@code gradle}. That last fallthrough used to fire from <em>any</em> directory, so running
 * {@code play start} one level above an application handed off to Gradle, which answered
 * "Directory '/x' does not contain a Gradle build" — while the shim's own, far more useful
 * "cd into your Play 1 application" text sat on the final branch, reachable only when
 * {@code gradle} is absent from PATH. The guidance was therefore invisible to exactly the
 * people most likely to have Gradle installed.
 *
 * <p>Every test here puts a stub {@code gradle} on PATH, which is what makes them meaningful:
 * the regression only appears when a system Gradle exists to fall through to, so a suite that
 * relied on the host not having one would pass against the broken shim on most machines. The
 * stub also keeps the assertions independent of whatever real Gradle is installed.
 *
 * <p>Sibling of {@code BundleLauncherTest} in the gradle-plugin suite, which drives the other
 * shipped shell script the same way. This one lives in the framework suite because the shim is
 * a repo-root file that {@code ant package} bundles, not a gradle-plugin resource.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the play shim is #!/bin/sh and needs a POSIX shell")
public class PlayShimTest {

    /** Marker the stub Gradle prints, so a delegation is distinguishable from a refusal. */
    private static final String DELEGATED = "STUB-GRADLE-INVOKED";

    private static File shim() {
        // The forked test JVM runs with basedir = framework/, so the shim is one level up.
        File f = new File(System.getProperty("user.dir"), "../play");
        if (!f.isFile()) {
            throw new IllegalStateException("play shim not found at " + f.getAbsolutePath());
        }
        return f;
    }

    /** A directory holding an executable `gradle` that only announces itself. */
    private static File stubGradleBin(File parent) throws IOException {
        File bin = new File(parent, "stubbin");
        bin.mkdirs();
        File gradle = new File(bin, "gradle");
        Files.writeString(gradle.toPath(), "#!/bin/sh\necho " + DELEGATED + " \"$@\"\n",
            StandardCharsets.UTF_8);
        gradle.setExecutable(true);
        return bin;
    }

    /** Run the shim in [cwd] with the stub bin prepended to PATH; return exit code + output. */
    private static Result run(File cwd, File stubBin, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = shim().getAbsolutePath();
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true);
        pb.environment().put("PATH",
            stubBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    private record Result(int exitCode, String output) {}

    /** Give [dir] the marker file that makes Gradle — and now the shim — call it a build. */
    private static void makeGradleBuild(File dir) throws IOException {
        Files.writeString(new File(dir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"x\"\n", StandardCharsets.UTF_8);
    }

    /** Scaffold just enough of an application under [parent] for the suggestion to find it. */
    private static void makeAppDir(File parent, String name) throws IOException {
        File app = new File(parent, name);
        new File(app, "conf").mkdirs();
        Files.writeString(new File(app, "build.gradle.kts").toPath(), "\n", StandardCharsets.UTF_8);
        Files.writeString(new File(app, "conf/application.conf").toPath(), "\n", StandardCharsets.UTF_8);
    }

    @Test
    public void refusesOutsideAnApplicationEvenWhenGradleIsOnPath(@TempDir File tmp) throws Exception {
        File cwd = new File(tmp, "elsewhere");
        cwd.mkdirs();

        Result r = run(cwd, stubGradleBin(tmp), "start");

        assertThat(r.output()).contains("is not a Play application directory");
        assertThat(r.output()).contains("cd into your Play 1 application");
        // The regression itself: with a gradle on PATH the shim used to delegate, and the
        // user got Gradle's "does not contain a Gradle build" instead of the line above.
        assertThat(r.output()).doesNotContain(DELEGATED);
        assertThat(r.exitCode()).isNotZero();
    }

    @Test
    public void namesAnApplicationSittingDirectlyBelow(@TempDir File tmp) throws Exception {
        File cwd = new File(tmp, "container");
        cwd.mkdirs();
        makeAppDir(cwd, "myapp");
        // A plain directory next door must not be mistaken for an application.
        new File(cwd, "notanapp").mkdirs();

        Result r = run(cwd, stubGradleBin(tmp), "start");

        assertThat(r.output()).contains("There is an application directly below this one");
        assertThat(r.output()).contains("cd myapp");
        assertThat(r.output()).doesNotContain("cd notanapp");
    }

    @Test
    public void saysNothingAboutNeighboursWhenThereAreNone(@TempDir File tmp) throws Exception {
        File cwd = new File(tmp, "bare");
        cwd.mkdirs();

        Result r = run(cwd, stubGradleBin(tmp), "start");

        assertThat(r.output()).contains("is not a Play application directory");
        assertThat(r.output()).doesNotContain("There is an application directly below this one");
    }

    @Test
    public void stillDelegatesToGradleInsideARealGradleBuild(@TempDir File tmp) throws Exception {
        // The fix must not swallow the legitimate fallthrough: in a directory that really is
        // a Gradle build, Gradle's own diagnostics are the accurate ones and should surface.
        File cwd = new File(tmp, "gradleproject");
        cwd.mkdirs();
        makeGradleBuild(cwd);

        Result r = run(cwd, stubGradleBin(tmp), "status");

        assertThat(r.output()).contains(DELEGATED);
        assertThat(r.output()).doesNotContain("is not a Play application directory");
    }

    @Test
    public void newIsUnaffectedByTheDirectoryCheck(@TempDir File tmp) throws Exception {
        // `play new` scaffolds from anywhere and never goes through find_gradle. Asserted via
        // the no-name usage error, which is reached before any runner is chosen — cheap, and
        // enough to catch the directory guard being hoisted somewhere it does not belong.
        File cwd = new File(tmp, "anywhere");
        cwd.mkdirs();

        Result r = run(cwd, stubGradleBin(tmp), "new");

        assertThat(r.output()).contains("Usage: play new <name>");
        assertThat(r.output()).doesNotContain("is not a Play application directory");
    }
}
