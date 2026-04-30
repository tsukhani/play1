package play.deps;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry;
import org.apache.ivy.util.DefaultMessageLogger;
import org.apache.ivy.util.Message;
import org.apache.ivy.util.filter.FilterHelper;

import play.libs.Files;

import java.io.File;
import java.io.FileFilter;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves {@code framework/dependencies.yml} via Ivy and populates
 * {@code framework/lib/} with the matching jar set. Replaces the historical
 * manual "curl from Maven Central + verify SHA-256 + drop into lib/" flow
 * used when adding new framework-level deps (PF-62).
 *
 * <p>Standalone from {@link DependenciesManager}: that class is wired for
 * resolving an application's {@code conf/dependencies.yml} against the
 * framework as a transitive parent, and its path conventions
 * ({@code application.path} hard-coded to {@code conf/dependencies.yml},
 * {@link DependenciesManager#isFrameworkLocal} skipping anything already in
 * {@code framework/lib/}) make framework-self-resolution awkward to drive
 * through it. This class shares the smaller building blocks
 * ({@link YamlParser}, {@link SettingsParser}, {@link HumanReadyLogger}) but
 * runs Ivy with a manifest-only path tailored for this use case.
 *
 * <p>Idempotent: a re-run with no manifest changes produces no jar churn —
 * existing files matching the resolved artifact (by name and size) are left
 * in place.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code framework.path} — absolute path to the {@code framework/}
 *       directory. Required.
 *   <li>{@code prune} — if {@code "true"}, delete jars in {@code lib/} that
 *       aren't in the resolved set. Default: report-only, so PR diffs still
 *       capture removals deliberately.
 *   <li>{@code verbose} — enable Ivy's INFO-level resolution logging.
 * </ul>
 */
public class FrameworkResolve {

    /**
     * Shaded vendored jars in framework/lib/ that have no canonical Maven
     * coordinates and so can't be declared in dependencies.yml. Each was
     * repackaged with a {@code jj.play.*} prefix to avoid classpath conflicts
     * with apps bringing their own copies of the underlying libraries.
     * Treated as known-intentional during stray detection (PF-63).
     */
    private static final Set<String> VENDORED_JARS = Set.of(
        "jj-wikitext.jar",      // Eclipse Mylyn WikiText, used by modules/docviewer
        "jj-textile.jar",       // Eclipse Mylyn Textile dialect, used by modules/docviewer
        "jj-simplecaptcha.jar"  // SimpleCaptcha, used by framework/src/play/libs/Images.java
    );

    public static void main(String[] args) throws Exception {
        String frameworkPath = System.getProperty("framework.path");
        if (frameworkPath == null) {
            System.err.println("ERROR: framework.path system property is required");
            System.exit(1);
        }

        File frameworkDir = new File(frameworkPath);
        File depsFile = new File(frameworkDir, "dependencies.yml");
        if (!depsFile.exists()) {
            System.err.println("ERROR: " + depsFile.getAbsolutePath() + " does not exist");
            System.exit(1);
        }

        File libDir = new File(frameworkDir, "lib");
        libDir.mkdirs();

        // dependencies.yml's repository definitions reference ${play.path}; set it
        // to the project root so the localRepo and module-discovery patterns
        // resolve correctly. DependenciesManager.resolve does the same at line 380.
        System.setProperty("play.path", frameworkDir.getParentFile().getAbsolutePath());

        System.out.println("~ Resolving " + depsFile.getAbsolutePath());
        System.out.println("~");

        ModuleDescriptorParserRegistry.getInstance().addParser(new YamlParser());

        HumanReadyLogger logger = new HumanReadyLogger();
        IvySettings ivySettings = new IvySettings();
        new SettingsParser(logger).parse(ivySettings, depsFile);
        ivySettings.setDefaultResolver("mavenCentral");
        ivySettings.setDefaultUseOrigin(true);

        Ivy ivy = Ivy.newInstance(ivySettings);
        if (System.getProperty("verbose") != null && !"false".equalsIgnoreCase(System.getProperty("verbose"))) {
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_INFO));
        } else {
            ivy.getLoggerEngine().setDefaultLogger(logger);
        }

        ResolveOptions options = new ResolveOptions();
        options.setConfs(new String[] { "default" });
        // jar + bundle covers Maven jars and OSGi-style bundles. Sources stay
        // in the Ivy cache and are never copied to lib/.
        options.setArtifactFilter(FilterHelper.getArtifactTypeFilter(new String[] { "jar", "bundle" }));

        ResolveReport report = ivy.getResolveEngine().resolve(depsFile.toURI().toURL(), options);

        Set<File> kept = new HashSet<>();
        boolean anyMissing = false;
        int copied = 0;

        for (IvyNode node : report.getDependencies()) {
            if (node.hasProblem() && !node.isCompletelyEvicted()) {
                System.err.println("~ MISSING: " + node.getId() + " — " + node.getProblemMessage());
                anyMissing = true;
                continue;
            }
            if (!node.isLoaded() || node.isCompletelyEvicted()) {
                continue;
            }
            for (ArtifactDownloadReport artifact : report.getArtifactsReports(node.getResolvedId())) {
                File from = artifact.getLocalFile();
                if (from == null) {
                    System.err.println("~ MISSING: " + artifact.getArtifact());
                    anyMissing = true;
                    continue;
                }
                File to = new File(libDir, from.getName());
                if (!to.exists() || to.length() != from.length()) {
                    Files.copy(from, to);
                    System.out.println("~  +lib/" + to.getName());
                    copied++;
                }
                kept.add(to);
            }
        }

        File[] existing = libDir.listFiles((FileFilter) f -> f.getName().endsWith(".jar"));
        boolean prune = "true".equalsIgnoreCase(System.getProperty("prune"));
        int strays = 0;
        if (existing != null) {
            for (File f : existing) {
                if (kept.contains(f) || VENDORED_JARS.contains(f.getName())) {
                    continue;
                }
                if (prune) {
                    if (f.delete()) {
                        System.out.println("~  -lib/" + f.getName() + " (pruned)");
                        strays++;
                    }
                } else {
                    System.out.println("~  ?lib/" + f.getName() + " (stray; not in dependencies.yml — re-run with -Dprune=true to delete)");
                    strays++;
                }
            }
        }

        System.out.println("~");
        System.out.println("~ Resolved " + kept.size() + " artifact(s); " + copied + " copied, " + strays + (prune ? " pruned" : " stray"));
        if (anyMissing) {
            System.err.println("~ Resolution had errors");
            System.exit(1);
        }
        System.out.println("~ Done");
    }
}
