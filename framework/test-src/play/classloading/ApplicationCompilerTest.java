package play.classloading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
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
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.CompilationException;
import play.vfs.VirtualFile;

/**
 * PF-229: Play compiles the application with the JDK's javac, in memory, where ECJ compiled it
 * before. These pin what the classes that come out of it carry, which of the application's
 * classes javac reads from where, and how a compile error reaches the DEV error page.
 */
class ApplicationCompilerTest {

    @TempDir
    File app;

    private Properties savedConfiguration;
    private ApplicationClasses savedClasses;
    private List<VirtualFile> savedJavaPath;
    private File savedApplicationPath;

    @BeforeEach
    void setUp() {
        savedConfiguration = Play.configuration;
        savedClasses = Play.classes;
        savedJavaPath = Play.javaPath;
        savedApplicationPath = Play.applicationPath;

        Play.configuration = new Properties();
        Play.classes = new ApplicationClasses();
        Play.applicationPath = app;
        Play.javaPath = new CopyOnWriteArrayList<>(List.of(VirtualFile.open(new File(app, "app"))));
    }

    @AfterEach
    void tearDown() {
        Play.configuration = savedConfiguration;
        Play.classes = savedClasses;
        Play.javaPath = savedJavaPath;
        Play.applicationPath = savedApplicationPath;
    }

    @Test
    void everyClassOfAFileGoesToApplicationClassesWithLineNumbersLocalVariablesAndParameterNames() throws Exception {
        source("models/Order.java", """
            package models;
            public class Order {
                public static class Line { public int quantity; }
                public Runnable task(String label) {
                    int count = label.length();
                    class Local implements Runnable { public void run() { System.out.println(count); } }
                    return new Local();
                }
                public Object anonymous() { return new Object() { }; }
            }
            """);

        compile("models.Order");

        for (String name : List.of("models.Order", "models.Order$Line", "models.Order$1Local", "models.Order$1")) {
            ApplicationClass compiled = Play.classes.getApplicationClass(name);
            assertThat(compiled).as(name).isNotNull();
            assertThat(compiled.javaByteCode).as(name + " compiled").isNotNull();
            assertThat(compiled.compiled).as(name).isTrue();
        }
        ClassModel order = ClassFile.of().parse(Play.classes.getApplicationClass("models.Order").javaByteCode);
        assertThat(order.majorVersion()).isEqualTo(ClassFile.JAVA_25_VERSION);
        assertThat(order.minorVersion()).isZero();
        assertThat(order.findAttribute(Attributes.sourceFile())).isPresent();

        MethodModel task = method(order, "task");
        // what action binding reads through Method.getParameters()
        assertThat(task.findAttribute(Attributes.methodParameters()).orElseThrow().parameters())
            .extracting(parameter -> parameter.name().orElseThrow().stringValue()).containsExactly("label");
        CodeAttribute code = task.findAttribute(Attributes.code()).orElseThrow();
        // what LocalvariablesNamesEnhancer and SigEnhancer read
        assertThat(code.findAttribute(Attributes.localVariableTable()).orElseThrow().localVariables())
            .extracting(local -> local.name().stringValue()).contains("this", "label", "count");
        assertThat(code.findAttribute(Attributes.lineNumberTable())).isPresent();
    }

    @Test
    void aClassOfTheApplicationThatIsNotCompiledYetIsCompiledFromItsSourceToo() throws Exception {
        source("controllers/Orders.java", """
            package controllers;
            public class Orders { public static String total() { return models.Price.format(42); } }
            """);
        source("models/Price.java", """
            package models;
            public class Price { public static String format(int cents) { return cents + " cents"; } }
            """);

        compile("controllers.Orders");

        assertThat(Play.classes.getApplicationClass("models.Price").javaByteCode).isNotNull();
    }

    @Test
    void aClassCompiledEarlierIsReadAsPlayCompiledItAndNotFromItsSource() throws Exception {
        File price = source("models/Price.java", """
            package models;
            public class Price { public static String format(int cents) { return cents + " cents"; } }
            """);
        compile("models.Price");
        byte[] first = Play.classes.getApplicationClass("models.Price").javaByteCode;

        // Not refreshed, so to Play the class is the one compiled above; its file now fails to compile.
        Files.writeString(price.toPath(), "package models; public class Price { broken }", StandardCharsets.UTF_8);
        source("controllers/Orders.java", """
            package controllers;
            public class Orders { public static String total() { return models.Price.format(42); } }
            """);
        compile("controllers.Orders");

        assertThat(Play.classes.getApplicationClass("controllers.Orders").javaByteCode).isNotNull();
        assertThat(Play.classes.getApplicationClass("models.Price").javaByteCode).isSameAs(first);
    }

    @Test
    void theApplicationsSourceWinsOverAClassOfTheSameNameOnTheClasspath() throws Exception {
        // As Gradle's compileJava output does: build/classes is on the classpath of a Gradle start.
        // The test classpath has this fixture, and the source is older than its class, which is
        // the one javac takes when it has both to choose from.
        File older = source("play/classloading/PropertiesEnhancerFixture.java", """
            package play.classloading;
            public class PropertiesEnhancerFixture { public static String onlyInTheSource() { return "source"; } }
            """);
        assertThat(older.setLastModified(946_684_800_000L)).isTrue();
        source("controllers/Uses.java", """
            package controllers;
            public class Uses { public static String call() { return play.classloading.PropertiesEnhancerFixture.onlyInTheSource(); } }
            """);

        compile("controllers.Uses");

        assertThat(Play.classes.getApplicationClass("play.classloading.PropertiesEnhancerFixture").javaByteCode).isNotNull();
    }

    @Test
    void aCompileErrorNamesTheFileTheLineAndTheCharactersOfTheError() throws Exception {
        File file = source("controllers/Broken.java", """
            package controllers;
            public class Broken {
                public static void index() {
                    Strin name = "x";
                }
            }
            """);
        String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> compile("controllers.Broken"))
            .isInstanceOfSatisfying(CompilationException.class, e -> {
                assertThat(e.getSourceFile()).isEqualTo("/app/controllers/Broken.java");
                assertThat(e.getLineNumber()).isEqualTo(4);
                assertThat(e.getMessage()).contains("cannot find symbol").contains("Strin");
                assertThat(e.isSourceAvailable()).isTrue();
                // the characters the error page marks: CompilationException's end is the last one, inclusive
                assertThat(source.substring(e.getSourceStart(), e.getSourceEnd() + 1)).isEqualTo("Strin");
                assertThat(e.getSource()).anyMatch(line -> line.contains("\000Strin\001"));
            });
    }

    @Test
    void aPreviewApiCompilesWithJavaEnablePreviewIntoAClassMarkedAsPreview() throws Exception {
        source("models/Lazy.java", """
            package models;
            public class Lazy {
                public static String value() {
                    StableValue<String> value = StableValue.of();
                    return value.orElseSet(() -> "preview");
                }
            }
            """);

        assertThatThrownBy(() -> compile("models.Lazy"))
            .isInstanceOfSatisfying(CompilationException.class, e -> {
                assertThat(e.getMessage()).contains("preview API");
                assertThat(e.getLineNumber()).isEqualTo(4);
            });

        Play.configuration.setProperty("java.enablePreview", "true");
        Play.classes = new ApplicationClasses();
        compile("models.Lazy");

        ClassModel lazy = ClassFile.of().parse(Play.classes.getApplicationClass("models.Lazy").javaByteCode);
        // what the JVM refuses to load without --enable-preview
        assertThat(lazy.minorVersion()).isEqualTo(ClassFile.PREVIEW_MINOR_VERSION).isEqualTo(65535);
        assertThat(lazy.majorVersion()).isEqualTo(Runtime.version().feature() + 44);
    }

    @Test
    void theOptionsAreWhatEcjsSettingsAskedForAndTheApplicationsReleaseAndPreview() {
        Properties conf = new Properties();
        String release = Integer.toString(Runtime.version().feature());

        assertThat(ApplicationCompiler.options(conf))
            .containsSubsequence("-g", "-parameters", "-encoding", "UTF-8")
            .contains("-proc:none", "-implicit:class", "-Xpkginfo:always")
            .doesNotContain("--enable-preview");
        conf.setProperty("java.source", release);
        assertThat(ApplicationCompiler.options(conf)).containsSubsequence("-source", release, "-target", release);

        conf.setProperty("java.enablePreview", " TRUE ");
        assertThat(ApplicationCompiler.options(conf)).contains("--enable-preview");
        conf.setProperty("java.enablePreview", "false");
        assertThat(ApplicationCompiler.options(conf)).doesNotContain("--enable-preview");
        // a typo is no reason to compile without what the application asked for
        conf.setProperty("java.enablePreview", "yes");
        assertThatThrownBy(() -> ApplicationCompiler.options(conf)).isInstanceOf(CompilationException.class)
            .hasMessageContaining("java.enablePreview");

        conf.remove("java.enablePreview");
        conf.setProperty("java.source", "24");
        assertThatThrownBy(() -> ApplicationCompiler.options(conf)).isInstanceOf(CompilationException.class)
            .hasMessageContaining("Incompatible Java version specified (24)");
        conf.setProperty("java.source", Integer.toString(Runtime.version().feature() + 1));
        assertThatThrownBy(() -> ApplicationCompiler.options(conf)).isInstanceOf(CompilationException.class);
    }

    private static MethodModel method(ClassModel model, String name) {
        return model.methods().stream().filter(method -> method.methodName().equalsString(name)).findFirst().orElseThrow();
    }

    private void compile(String... classNames) {
        Play.classes.compiler().compile(classNames);
    }

    private File source(String path, String content) throws IOException {
        File file = new File(app, "app/" + path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }
}
