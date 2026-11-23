package play.classloading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.ApplicationCompiler.Jvm;
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
    void aCompileOnAnInterruptedThreadReadsTheJarsAndLeavesThemReadableForTheNextCompile() throws Exception {
        // A DEV reload ran Play.start() on an interrupted thread, and javac's first read of a jar under
        // that interrupt closed the jar's channel for good: every later compile failed with "bad class
        // file ... ClosedChannelException", which only a new JVM ended.
        source("models/Order.java", """
            package models;
            @jakarta.persistence.Entity public class Order { }
            """);
        source("models/Line.java", """
            package models;
            @jakarta.persistence.Entity public class Line { jakarta.persistence.CascadeType cascade; }
            """);

        Thread.currentThread().interrupt();
        try {
            compile("models.Order");
            assertThat(Thread.currentThread().isInterrupted()).as("the caller keeps its interrupt status").isTrue();
        } finally {
            Thread.interrupted();
        }
        compile("models.Line");

        assertThat(Play.classes.getApplicationClass("models.Order").javaByteCode).isNotNull();
        assertThat(Play.classes.getApplicationClass("models.Line").javaByteCode).isNotNull();
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
            public class Uses {
                public static String call() { return play.classloading.PropertiesEnhancerFixture.onlyInTheSource(); }
                // a class of the same package on the classpath that the application has no source of stays there
                public static Class<?> other() { return play.classloading.LocalVariablesFixture.class; }
            }
            """);

        compile("controllers.Uses");

        assertThat(Play.classes.getApplicationClass("play.classloading.PropertiesEnhancerFixture").javaByteCode).isNotNull();
        assertThat(Play.classes.getApplicationClass("play.classloading.LocalVariablesFixture")).isNull();
    }

    @Test
    void aPackageTheJvmExportsToTheApplicationIsOneItsClassesCompileAgainst() throws Exception {
        source("controllers/Native.java", """
            package controllers;
            public class Native {
                public static long address(java.nio.ByteBuffer buffer) { return ((sun.nio.ch.DirectBuffer) buffer).address(); }
            }
            """);
        // as the JVM refuses it: this one has no --add-exports java.base/sun.nio.ch=ALL-UNNAMED
        assertThatThrownBy(() -> compile("controllers.Native")).isInstanceOfSatisfying(CompilationException.class,
            e -> assertThat(e.getMessage()).contains("package sun.nio.ch is not visible"));

        // What --add-exports or --add-opens does, here to the unnamed module of a loader of the test's own,
        // as the play jar, this JVM's agent, has the means to.
        Module application = new URLClassLoader(new URL[0]).getUnnamedModule();
        assertThat(HotswapAgent.instrumentation).as("the play jar as this JVM's agent").isNotNull();
        HotswapAgent.instrumentation.redefineModule(Object.class.getModule(), Set.of(),
            Map.of("sun.nio.ch", Set.of(application)), Map.of(), Set.of(), Map.of());
        Jvm jvm = Jvm.current(application);
        assertThat(jvm.exports()).contains("java.base/sun.nio.ch");
        assertThat(Jvm.current(getClass().getModule()).exports()).doesNotContain("java.base/sun.nio.ch");
        List<String> options = ApplicationCompiler.options(Play.configuration, jvm);
        assertThat(options).containsSubsequence("--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED");

        Play.classes = new ApplicationClasses();
        new ApplicationCompiler(Play.classes, options).compile(new String[] { "controllers.Native" });
        assertThat(Play.classes.getApplicationClass("controllers.Native").javaByteCode).isNotNull();
    }

    @Test
    void aModuleTheJvmResolvedIsOneTheApplicationsClassesCompileAgainst() throws Exception {
        source("controllers/Vectors.java", """
            package controllers;
            public class Vectors { public static int lanes() { return jdk.incubator.vector.IntVector.SPECIES_PREFERRED.length(); } }
            """);
        // as the JVM refuses it: this one has no --add-modules jdk.incubator.vector
        assertThat(Jvm.current(getClass().getModule()).modules()).contains("java.base", "java.sql").doesNotContain("jdk.incubator.vector");
        assertThatThrownBy(() -> compile("controllers.Vectors")).isInstanceOf(CompilationException.class);

        // what Jvm.current finds in a JVM that has it
        Jvm jvm = new Jvm(false, List.of("java.base", "jdk.incubator.vector"), List.of());
        List<String> options = ApplicationCompiler.options(Play.configuration, jvm);
        assertThat(options).containsSubsequence("--add-modules", "java.base,jdk.incubator.vector");

        Play.classes = new ApplicationClasses();
        new ApplicationCompiler(Play.classes, options).compile(new String[] { "controllers.Vectors" });
        assertThat(Play.classes.getApplicationClass("controllers.Vectors").javaByteCode).isNotNull();
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
        // a JVM started with --enable-preview loads what it compiles to, and this one was not
        assertThat(Jvm.runsWithPreview()).isFalse();
        assertThat(ApplicationCompiler.options(conf, new Jvm(true, List.of(), List.of()))).contains("--enable-preview");
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

    @Test
    void aMethodReferenceToAnEntitysInheritedFinderIsACompileErrorAtItsLine() throws Exception {
        // PF-242: the method handle names GenericModel, whose finder throws, so the reference is refused here
        book();
        source("controllers/Shelf.java", """
            package controllers;
            import java.util.List;
            import java.util.function.LongSupplier;
            public class Shelf {
                public static List<Object> byIds(List<Long> ids) {
                    return ids.stream().map(models.Book::findById).toList();
                }
            }
            """);

        assertThatThrownBy(() -> compile("controllers.Shelf"))
            .isInstanceOfSatisfying(CompilationException.class, e -> {
                assertThat(e.getSourceFile()).isEqualTo("/app/controllers/Shelf.java");
                assertThat(e.getLineNumber()).isEqualTo(6);
                assertThat(e.getErrorDescription()).contains("models.Book::findById",
                        "Write a lambda instead: id -> models.Book.findById(id)");
            });
    }

    @Test
    void everyPlaceholderFinderIsRefusedWithTheLambdaThatCallsIt() throws Exception {
        book();
        Map<String, String> references = Map.of(
                "java.util.function.LongSupplier r = Book::count;", "() -> Book.count()",
                "java.util.function.Supplier<java.util.List<Book>> r = Book::findAll;", "() -> Book.findAll()",
                "java.util.function.Supplier<Object> r = Book::all;", "() -> Book.all()",
                "java.util.function.IntSupplier r = Book::deleteAll;", "() -> Book.deleteAll()",
                "java.util.function.BiFunction<String, Object[], Object> r = Book::find;",
                        "(query, params) -> Book.find(query, params)",
                "java.util.function.BiFunction<String, Object[], Integer> r = Book::delete;",
                        "(query, params) -> Book.delete(query, params)",
                "java.util.function.BiFunction<String, Object[], Long> r = Book::count;",
                        "(query, params) -> Book.count(query, params)",
                "java.util.function.BiFunction<String, play.mvc.Scope.Params, Object> r = Book::create;",
                        "(name, params) -> Book.create(name, params)",
                "java.util.function.LongSupplier r = play.db.jpa.GenericModel::count;",
                        "() -> play.db.jpa.GenericModel.count()");
        int n = 0;
        for (Map.Entry<String, String> reference : references.entrySet()) {
            String name = "Ref" + n++;
            Play.classes = new ApplicationClasses();
            source("controllers/" + name + ".java", "package controllers;\nimport models.Book;\npublic class " + name
                    + " {\n    static void use() {\n        " + reference.getKey() + "\n    }\n}\n");
            assertThatThrownBy(() -> compile("controllers." + name)).as(reference.getKey())
                .isInstanceOfSatisfying(CompilationException.class, e -> {
                    assertThat(e.getLineNumber()).isEqualTo(5);
                    assertThat(e.getErrorDescription()).contains("Write a lambda instead: " + reference.getValue());
                });
        }
    }

    @Test
    void aLambdaAnEntitysOwnStaticMethodAndOtherMethodReferencesCompileAsBefore() throws Exception {
        book();
        source("models/Shelf.java", """
            package models;
            import java.util.List;
            import java.util.regex.Matcher;
            @jakarta.persistence.Entity
            public class Shelf extends play.db.jpa.Model {
                public static Shelf create(String name) {
                    return new Shelf();
                }
                public static List<Object> byIds(List<Long> ids) {
                    return ids.stream().map(id -> (Object) Book.findById(id)).toList();
                }
                public static List<Shelf> named(List<String> names) {
                    return names.stream().map(Shelf::create).toList();
                }
                public static long found(List<Matcher> matchers) {
                    return matchers.stream().filter(Matcher::find).map(String::valueOf).count();
                }
                public static Object real(java.util.function.Function<Object, Object> f) {
                    return f.apply(null);
                }
            }
            """);

        compile("models.Shelf");

        assertThat(Play.classes.getApplicationClass("models.Shelf").javaByteCode).isNotNull();
    }

    @Test
    void aFileWithTwoTopLevelClassesReportsItsReferenceOnce() throws Exception {
        book();
        source("controllers/Twice.java", """
            package controllers;
            public class Twice {
                static Object first() {
                    return ((java.util.function.LongSupplier) models.Book::count).getAsLong();
                }
            }
            class Second {
            }
            """);

        assertThatThrownBy(() -> compile("controllers.Twice"))
            .isInstanceOfSatisfying(CompilationException.class, e -> assertThat(e.getLineNumber()).isEqualTo(4));
    }

    private void book() throws IOException {
        source("models/Book.java", """
            package models;
            @jakarta.persistence.Entity
            public class Book extends play.db.jpa.Model {
                public String title;
            }
            """);
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
