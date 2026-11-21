package play.classloading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.plugins.PluginCollection;
import play.vfs.VirtualFile;

/**
 * PF-229: a DEV reload compiles every changed class in one javac task. Each task costs
 * milliseconds of its own, about 20 ms in an application with a large package where ECJ's took
 * about 1, so compiling the classes of a pull of a hundred changes one by one took seconds.
 */
class DetectChangesCompileTest {

    @TempDir
    File app;

    private Properties savedConfiguration;
    private ApplicationClasses savedClasses;
    private List<VirtualFile> savedJavaPath;
    private File savedApplicationPath;
    private PluginCollection savedPlugins;
    private boolean savedUsePrecompiled;
    private ApplicationClassloader savedClassloader;

    @BeforeEach
    void setUp() {
        savedConfiguration = Play.configuration;
        savedClasses = Play.classes;
        savedJavaPath = Play.javaPath;
        savedApplicationPath = Play.applicationPath;
        savedPlugins = Play.pluginCollection;
        savedUsePrecompiled = Play.usePrecompiled;
        savedClassloader = Play.classloader;

        Play.configuration = new Properties();
        Play.classes = new ApplicationClasses();
        Play.applicationPath = app;
        Play.javaPath = new CopyOnWriteArrayList<>(List.of(VirtualFile.open(new File(app, "app"))));
        Play.usePrecompiled = false;
        Play.pluginCollection = new PluginCollection();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = savedConfiguration;
        Play.classes = savedClasses;
        Play.javaPath = savedJavaPath;
        Play.applicationPath = savedApplicationPath;
        Play.pluginCollection = savedPlugins;
        Play.usePrecompiled = savedUsePrecompiled;
        Play.classloader = savedClassloader;
    }

    @Test
    void theChangedClassesAreCompiledInOneCompileAndHotSwapped() throws Exception {
        for (String name : List.of("First", "Second", "Third")) {
            source("models/" + name + ".java", "package models;\npublic class " + name
                + " { public static String text() { return \"" + name + " one\"; } }\n");
        }
        ApplicationClassloader loader = new ApplicationClassloader();
        Play.classloader = loader;
        Class<?> first = loader.loadClass("models.First");
        Class<?> second = loader.loadClass("models.Second");
        Class<?> third = loader.loadClass("models.Third");
        assertThat(text(first)).isEqualTo("First one");

        List<List<String>> compiles = new CopyOnWriteArrayList<>();
        Field compiler = ApplicationClasses.class.getDeclaredField("compiler");
        compiler.setAccessible(true);
        compiler.set(Play.classes, new ApplicationCompiler(Play.classes, ApplicationCompiler.options(Play.configuration)) {
            @Override
            public void compile(String[] classNames) {
                compiles.add(List.of(classNames));
                super.compile(classNames);
            }
        });
        edit("models/First.java", "First one", "First two");
        edit("models/Second.java", "Second one", "Second two");

        loader.detectChanges();

        assertThat(compiles).hasSize(1);
        assertThat(compiles.get(0)).containsExactlyInAnyOrder("models.First", "models.Second");
        // the same classes, redefined in place
        assertThat(text(first)).isEqualTo("First two");
        assertThat(text(second)).isEqualTo("Second two");
        assertThat(text(third)).isEqualTo("Third one");

        loader.detectChanges();
        assertThat(compiles).as("nothing changed since").hasSize(1);
    }

    @Test
    void aNestedNameTheCompiledFileDoesNotDeclareIsNoClassAndCompilesNothing() throws Exception {
        // Hibernate's ByteBuddy asks the loader for each proxy's name, models.Order$HibernateProxy$..., and
        // getJava finds Order.java for it: the file was compiled again for every proxied entity at each start
        source("models/Order.java", "package models;\npublic class Order {\n    public static class Line { }\n}\n");
        ApplicationClassloader loader = new ApplicationClassloader();
        Play.classloader = loader;
        loader.loadClass("models.Order");
        List<List<String>> compiles = countCompiles();

        String proxy = "models.Order$HibernateProxy$Q2f8aT1x";
        assertThat(loader.loadApplicationClass(proxy)).isNull();
        assertThatThrownBy(() -> loader.loadClass(proxy)).isInstanceOf(ClassNotFoundException.class);
        assertThat(loader.loadClass("models.Order$Line").getName()).isEqualTo("models.Order$Line");
        assertThat(compiles).as("the file was compiled as it is").isEmpty();
        assertThat(Play.classes.all()).extracting(c -> c.name).doesNotContain(proxy);

        loader.detectChanges();
        assertThat(compiles).as("no entry left for detectChanges to take for edited").isEmpty();

        // once the file has changed since its compile, a name that compile did not emit may be a class it gained
        edit("models/Order.java", "public static class Line { }", "public static class Line { }\n    public static class Fee { }");
        assertThat(new ApplicationClasses.ApplicationClass("models.Order$Fee").isNestedNameItsFileDoesNotDeclare()).isFalse();
    }

    private List<List<String>> countCompiles() throws Exception {
        List<List<String>> compiles = new CopyOnWriteArrayList<>();
        Field compiler = ApplicationClasses.class.getDeclaredField("compiler");
        compiler.setAccessible(true);
        compiler.set(Play.classes, new ApplicationCompiler(Play.classes, ApplicationCompiler.options(Play.configuration)) {
            @Override
            public void compile(String[] classNames) {
                compiles.add(List.of(classNames));
                super.compile(classNames);
            }
        });
        return compiles;
    }

    private static String text(Class<?> type) throws Exception {
        return (String) type.getMethod("text").invoke(null);
    }

    private void source(String path, String content) throws IOException {
        File file = new File(app, "app/" + path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    /** Replace text in a source, and date it a second later than it was, so that no clock granularity hides the edit. */
    private void edit(String path, String from, String to) throws IOException {
        File file = new File(app, "app/" + path);
        long before = file.lastModified();
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertThat(content).contains(from);
        Files.writeString(file.toPath(), content.replace(from, to), StandardCharsets.UTF_8);
        assertThat(file.setLastModified(before + 1000)).isTrue();
    }
}
