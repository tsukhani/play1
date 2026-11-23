package play.classloading;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.vfs.VirtualFile;

/**
 * PF-234: the JVMs that start an application from one tmp/ at once compile it once. These pin that a start which
 * waited for the lock takes the first start's javac output, every class of a file included, as a compile would
 * have given it; that an edit, an option or the class path gives a key of its own; and when nothing is shared.
 */
class SharedCompileTest {

    @TempDir
    File app;

    private Properties savedConfiguration;
    private ApplicationClasses savedClasses;
    private List<VirtualFile> savedJavaPath;
    private File savedApplicationPath;
    private File savedTmpDir;
    private boolean savedReadOnlyTmp;

    @BeforeEach
    void setUp() {
        savedConfiguration = Play.configuration;
        savedClasses = Play.classes;
        savedJavaPath = Play.javaPath;
        savedApplicationPath = Play.applicationPath;
        savedTmpDir = Play.tmpDir;
        savedReadOnlyTmp = Play.readOnlyTmp;

        Play.configuration = new Properties();
        Play.classes = new ApplicationClasses();
        Play.applicationPath = app;
        Play.javaPath = new CopyOnWriteArrayList<>(List.of(VirtualFile.open(new File(app, "app"))));
        Play.tmpDir = new File(app, "tmp");
        Play.readOnlyTmp = false;
        SharedCompile.forgetTheStart();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = savedConfiguration;
        Play.classes = savedClasses;
        Play.javaPath = savedJavaPath;
        Play.applicationPath = savedApplicationPath;
        Play.tmpDir = savedTmpDir;
        Play.readOnlyTmp = savedReadOnlyTmp;
    }

    @Test
    void aStartThatWaitedTakesTheFirstStartsOutputForEveryClassOfAFile() throws Exception {
        sources();

        Map<String, byte[]> first = new HashMap<>();
        try (SharedCompile shared = SharedCompile.acquire(sources("models.Order", "controllers.Home"), options())) {
            assertThat(shared).isNotNull();
            assertThat(shared.load(Play.classes)).as("nothing compiled yet").isFalse();
            Play.classes.compiler().compile(new String[] {"models.Order", "controllers.Home"});
            shared.store(Play.classes);
        }
        for (ApplicationClass compiled : Play.classes.all()) {
            if (compiled.javaByteCode != null) {
                first.put(compiled.name, compiled.javaByteCode);
            }
        }
        assertThat(first).containsKeys("models.Order", "models.Order$Line", "models.Order$1", "models.Receipt",
                "controllers.Home");

        Play.classes = new ApplicationClasses();
        SharedCompile.forgetTheStart(); // as another JVM
        try (SharedCompile shared = SharedCompile.acquire(sources("models.Order", "controllers.Home"), options())) {
            assertThat(shared.load(Play.classes)).isTrue();
        }
        for (Map.Entry<String, byte[]> entry : first.entrySet()) {
            ApplicationClass loaded = Play.classes.getApplicationClass(entry.getKey());
            assertThat(loaded).as(entry.getKey()).isNotNull();
            assertThat(loaded.compiled).as(entry.getKey()).isTrue();
            assertThat(loaded.javaByteCode).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        // the second top-level class of Order.java keeps the file it came from
        assertThat(Play.classes.getApplicationClass("models.Receipt").javaFile.getName()).isEqualTo("Order.java");
    }

    @Test
    void anEditAnOptionOrTheClassPathGivesAKeyOfItsOwn() throws Exception {
        sources();
        File jar = new File(app, "lib.jar");
        Files.writeString(jar.toPath(), "not really a jar");
        File classes = new File(app, "classes");
        classes.mkdirs();
        String classPath = jar + File.pathSeparator + classes;
        String key = SharedCompile.key(sources("models.Order", "controllers.Home"), options(), classPath);

        assertThat(SharedCompile.key(sources("models.Order", "controllers.Home"), options(), classPath))
                .as("the same inputs").isEqualTo(key);
        assertThat(SharedCompile.key(sources("models.Order", "controllers.Home"), List.of("-g"), classPath))
                .as("other options").isNotEqualTo(key);

        assertThat(jar.setLastModified(jar.lastModified() - 10_000)).isTrue();
        String touched = SharedCompile.key(sources("models.Order", "controllers.Home"), options(), classPath);
        assertThat(touched).as("a jar of another time").isNotEqualTo(key);

        Files.writeString(new File(classes, "Extra.class").toPath(), "x");
        String added = SharedCompile.key(sources("models.Order", "controllers.Home"), options(), classPath);
        assertThat(added).as("a class file more in a directory").isNotEqualTo(touched);

        source("controllers/Home.java", """
            package controllers;
            public class Home { public Object order() { return new models.Order(); } public int edited; }
            """);
        Play.classes = new ApplicationClasses();
        assertThat(SharedCompile.key(sources("models.Order", "controllers.Home"), options(), classPath))
                .as("an edited source").isNotEqualTo(added);
    }

    @Test
    void nothingIsSharedWithTheCacheOffATmpThatIsReadOnlyOrInARestart() throws Exception {
        sources();
        Play.configuration.setProperty("play.bytecodeCache", "false");
        assertThat(SharedCompile.acquire(sources("models.Order"), options())).isNull();

        SharedCompile.forgetTheStart();
        Play.configuration.remove("play.bytecodeCache");
        Play.readOnlyTmp = true;
        assertThat(SharedCompile.acquire(sources("models.Order"), options())).isNull();

        SharedCompile.forgetTheStart();
        Play.readOnlyTmp = false;
        try (SharedCompile first = SharedCompile.acquire(sources("models.Order"), options())) {
            assertThat(first).isNotNull();
        }
        assertThat(SharedCompile.acquire(sources("models.Order"), options())).as("a DEV restart in the same JVM")
                .isNull();
    }

    @Test
    void aClassCompiledBeforeTheStartsCompileIsInTheKeyAndKeptAsItIs() throws Exception {
        // a module's plugin is compiled while Play loads the plugins, before the start's compile
        sources();
        List<ApplicationClass> all = sources("models.Order", "controllers.Home");
        String before = SharedCompile.key(all, options(), "");
        Play.classes.compiler().compile(new String[] {"models.Order"});
        ApplicationClass order = Play.classes.getApplicationClass("models.Order");
        byte[] early = order.javaByteCode;
        assertThat(SharedCompile.key(all, options(), "")).as("javac reads it as a class file now").isNotEqualTo(before);

        try (SharedCompile shared = SharedCompile.acquire(all, options())) {
            assertThat(shared.load(Play.classes)).isFalse();
            Play.classes.compiler().compile(new String[] {"controllers.Home"});
            shared.store(Play.classes);
        }

        Play.classes = new ApplicationClasses();
        SharedCompile.forgetTheStart(); // as another JVM, which compiled the same plugin first
        List<ApplicationClass> again = sources("models.Order", "controllers.Home");
        Play.classes.compiler().compile(new String[] {"models.Order"});
        ApplicationClass sameOrder = Play.classes.getApplicationClass("models.Order");
        byte[] itsOwn = sameOrder.javaByteCode;
        assertThat(itsOwn).isEqualTo(early);
        try (SharedCompile shared = SharedCompile.acquire(again, options())) {
            assertThat(shared.load(Play.classes)).isTrue();
        }
        assertThat(sameOrder.javaByteCode).as("left as it was").isSameAs(itsOwn);
        assertThat(Play.classes.getApplicationClass("controllers.Home").javaByteCode).isNotNull();
    }

    @Test
    void anOutputThatCannotBeReadIsCompiledAgain() throws Exception {
        sources();
        List<ApplicationClass> all = sources("models.Order", "controllers.Home");
        String key = SharedCompile.key(all, options(), System.getProperty("java.class.path", ""));
        File output = new File(Play.tmpDir, "javac/" + key + ".bin");
        output.getParentFile().mkdirs();
        Files.write(output.toPath(), new byte[] {0, 0, 0, 1, 0, 0, 0, 9, 1});

        try (SharedCompile shared = SharedCompile.acquire(all, options())) {
            assertThat(shared.load(Play.classes)).isFalse();
        }
        assertThat(Play.classes.getApplicationClass("models.Order").javaByteCode).as("nothing registered").isNull();
    }

    @Test
    void aSecondStartWaitsUntilTheFirstHasDefinedItsClasses() throws Exception {
        sources();
        CountDownLatch firstHasTheLock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong released = new AtomicLong();
        AtomicLong secondGotIt = new AtomicLong();
        List<ApplicationClass> all = sources("models.Order", "controllers.Home");
        Thread first = Thread.ofPlatform().start(() -> {
            try (SharedCompile shared = SharedCompile.acquire(all, options())) {
                firstHasTheLock.countDown();
                release.await();
                released.set(System.nanoTime());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(firstHasTheLock.await(10, TimeUnit.SECONDS)).isTrue();
        SharedCompile.forgetTheStart(); // as another JVM
        Thread second = Thread.ofPlatform().start(() -> {
            try (SharedCompile shared = SharedCompile.acquire(all, options())) {
                secondGotIt.set(System.nanoTime());
            }
        });
        Thread.sleep(200);
        assertThat(secondGotIt.get()).as("still waiting").isZero();
        release.countDown();
        first.join(10_000);
        second.join(10_000);
        assertThat(secondGotIt.get()).isGreaterThan(released.get());
    }

    private static List<String> options() {
        return Play.classes.compiler().options;
    }

    /** As getAllClasses lists them: every source's class, read from its file. */
    private static List<ApplicationClass> sources(String... names) {
        List<ApplicationClass> all = new ArrayList<>();
        for (String name : names) {
            all.add(Play.classes.getApplicationClass(name));
        }
        return all;
    }

    private void sources() throws IOException {
        source("models/Order.java", """
            package models;
            public class Order {
                public static class Line { public int quantity; }
                public Object anonymous() { return new Object() { }; }
            }
            class Receipt { }
            """);
        source("controllers/Home.java", """
            package controllers;
            public class Home { public Object order() { return new models.Order(); } }
            """);
    }

    private File source(String path, String content) throws IOException {
        File file = new File(app, "app/" + path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }
}
