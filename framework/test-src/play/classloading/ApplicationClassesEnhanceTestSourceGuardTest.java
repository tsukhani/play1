package play.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.plugins.PluginCollection;
import play.vfs.VirtualFile;

/**
 * Pins the production-classpath safety guarantee in {@link ApplicationClasses.ApplicationClass#enhance()}:
 * during precompile, classes whose source lives under a {@code test/} root are compiled (so build errors
 * surface) but their enhanced bytecode is NOT written to {@code precompiled/java/} — keeping test code and
 * its JUnit dependency off the production classpath that {@code -Dprecompiled=true} force-loads. (PF-136)
 *
 * The enhancer chain itself is a no-op here (empty PluginCollection), so the only thing under test is the
 * {@code isTestSource()} write gate, exercised end-to-end against real source roots on disk.
 */
class ApplicationClassesEnhanceTestSourceGuardTest {

    private File baseDir;
    private File savedAppPath;
    private Properties savedConfig;
    private ApplicationClasses savedClasses;
    private PluginCollection savedPlugins;
    private List<VirtualFile> savedRoots;
    private String savedPrecompile;

    @BeforeEach
    void setUp() throws Exception {
        savedAppPath = Play.applicationPath;
        savedConfig = Play.configuration;
        savedClasses = Play.classes;
        savedPlugins = Play.pluginCollection;
        savedRoots = new ArrayList<>(Play.roots);
        savedPrecompile = System.getProperty("precompile");

        baseDir = Files.createTempDirectory("pf136-enhance").toFile();

        Play.applicationPath = baseDir;
        Play.configuration = new Properties();
        Play.classes = new ApplicationClasses();
        Play.pluginCollection = new PluginCollection(); // no enabled plugins -> enhance() chain is a no-op

        // Single application root: <baseDir> with app/ and test/ source dirs underneath.
        Play.roots.clear();
        Play.roots.add(VirtualFile.open(baseDir));
    }

    @AfterEach
    void tearDown() {
        Play.applicationPath = savedAppPath;
        Play.configuration = savedConfig;
        Play.classes = savedClasses;
        Play.pluginCollection = savedPlugins;
        Play.roots.clear();
        Play.roots.addAll(savedRoots);
        if (savedPrecompile == null) {
            System.clearProperty("precompile");
        } else {
            System.setProperty("precompile", savedPrecompile);
        }
        deleteRecursively(baseDir);
    }

    /** Read real compiled bytes of an existing class to use as valid fixture bytecode. */
    private static byte[] validBytecode() throws Exception {
        Class<?> c = PropertiesEnhancerFixture.class;
        try (InputStream in = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    /** Create a source file under <baseDir>/<sourceRoot>/<pkg-path>/<Simple>.java and return its VirtualFile. */
    private VirtualFile makeSource(String sourceRoot, String className) throws Exception {
        File f = new File(baseDir, sourceRoot + "/" + className.replace('.', '/') + ".java");
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), "// generated fixture source for " + className + "\n");
        return VirtualFile.open(f);
    }

    private ApplicationClass appClass(String className, VirtualFile javaFile) throws Exception {
        ApplicationClass ac = new ApplicationClass();
        ac.name = className;
        ac.javaFile = javaFile;
        ac.javaByteCode = validBytecode();
        ac.enhancedByteCode = ac.javaByteCode;
        Play.classes.add(ac);
        return ac;
    }

    private File precompiledFileFor(String className) {
        return new File(baseDir, "precompiled/java/" + className.replace('.', '/') + ".class");
    }

    @Test
    void writesPrecompiledClassForAppSourceButNotForTestSource() throws Exception {
        // A normal app-rooted class (app/) and a test-rooted class (test/).
        ApplicationClass appClass = appClass("models.Account", makeSource("app", "models.Account"));
        ApplicationClass testClass = appClass("models.AccountTest", makeSource("test", "models.AccountTest"));

        System.setProperty("precompile", "yes");
        appClass.enhance();
        testClass.enhance();

        File appOut = precompiledFileFor("models.Account");
        File testOut = precompiledFileFor("models.AccountTest");

        assertTrue(appOut.exists(), "app-rooted class MUST be written to precompiled/java/");
        assertFalse(testOut.exists(),
                "test-rooted class MUST NOT be written to precompiled/java/ (keeps test code + JUnit off the prod classpath)");

        // What WAS written is exactly the (unchanged, no-op-enhanced) bytecode.
        assertEquals(appClass.enhancedByteCode.length, Files.readAllBytes(appOut.toPath()).length,
                "written precompiled bytecode must match the enhanced bytecode");
    }

    @Test
    void writesNothingWhenPrecompileFlagAbsent() throws Exception {
        // Mutation/scope check: the write only happens under -Dprecompile. With the flag cleared,
        // even the app-rooted class is not written — so the test above is pinning the precompile path.
        ApplicationClass appClass = appClass("models.Account", makeSource("app", "models.Account"));

        System.clearProperty("precompile");
        appClass.enhance();

        assertFalse(precompiledFileFor("models.Account").exists(),
                "no precompiled output may be written when -Dprecompile is absent");
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }
}
