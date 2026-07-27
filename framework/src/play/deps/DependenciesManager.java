package play.deps;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the module load order from an application's {@code conf/dependencies.yml}.
 *
 * <p>This class used to drive Ivy for the {@code play deps} command — resolution,
 * artifact retrieval and install, and lib/modules syncing. That command was removed
 * in the 1.13.0 Gradle migration (PF-90), leaving those methods reachable only from
 * a {@code main()} nothing invoked; they were deleted rather than left as dead
 * weight on every app's classpath. Framework-level resolution lives in
 * {@link FrameworkResolve} and was always independent of this class.
 *
 * <p>Gradle-managed apps ship no {@code conf/dependencies.yml} — the play-gradle-plugin
 * populates {@code modules/} directly — so {@link #retrieveModules()} returns an empty
 * set for them and {@code Play.loadModules()} falls back to whatever is on disk (PF-90).
 */
public class DependenciesManager {

    final File application;

    public DependenciesManager(File application) {
        this.application = application;
    }

    // Retrieve the list of modules in the order they were defined in the dependencies.yml.
    public Set<String> retrieveModules() throws Exception {
        File ivyModule = new File(application, "conf/dependencies.yml");
        if (ivyModule == null || !ivyModule.exists()) {
            return new LinkedHashSet<>();
        }
        return YamlParser.getOrderedModuleList(ivyModule);
    }
}
