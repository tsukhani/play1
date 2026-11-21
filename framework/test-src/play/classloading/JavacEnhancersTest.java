package play.classloading;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.ControllersEnhancer;
import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer;
import play.plugins.EnhancerPlugin;
import play.plugins.PluginCollection;
import play.vfs.VirtualFile;

/**
 * PF-229: the framework's enhancers on what Play's javac compiles, which is what they get in
 * every application since javac replaced ECJ. Each class goes through ApplicationClass.enhance()
 * with the framework's own enhancer chain, EnhancerPlugin's, as in an application: properties,
 * signatures, controllers, mailers and local variable names. Those that read the local variable
 * tables and the parameter names are the ones a change of compiler could break.
 */
class JavacEnhancersTest {

    @TempDir
    File app;

    private Properties savedConfiguration;
    private ApplicationClasses savedClasses;
    private List<VirtualFile> savedJavaPath;
    private File savedApplicationPath;
    private PluginCollection savedPlugins;
    private boolean savedUsePrecompiled;

    @BeforeEach
    void setUp() {
        savedConfiguration = Play.configuration;
        savedClasses = Play.classes;
        savedJavaPath = Play.javaPath;
        savedApplicationPath = Play.applicationPath;
        savedPlugins = Play.pluginCollection;
        savedUsePrecompiled = Play.usePrecompiled;

        Play.configuration = new Properties();
        Play.classes = new ApplicationClasses();
        Play.applicationPath = app;
        Play.javaPath = new CopyOnWriteArrayList<>(List.of(VirtualFile.open(new File(app, "app"))));
        Play.usePrecompiled = false;
        Play.pluginCollection = new PluginCollection() {
            {
                addPlugin(new EnhancerPlugin());
            }
        };
    }

    @AfterEach
    void tearDown() {
        Play.configuration = savedConfiguration;
        Play.classes = savedClasses;
        Play.javaPath = savedJavaPath;
        Play.applicationPath = savedApplicationPath;
        Play.pluginCollection = savedPlugins;
        Play.usePrecompiled = savedUsePrecompiled;
        LocalVariablesNamesTracer.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void localVariableNamesAreTrackedAndParameterNamesSurviveTheEnhancers() throws Exception {
        source("helpers/Renderer.java", """
            package helpers;
            import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer;
            import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesSupport;
            public class Renderer implements LocalVariablesSupport {
                public java.util.Map<String, Object> render(int first, long second, String label) {
                    int local = first + 1;
                    for (String item : java.util.List.of(label)) {
                        String inLoop = item + local;
                    }
                    return new java.util.HashMap<>(LocalVariablesNamesTracer.getLocalVariables());
                }
            }
            """);

        Class<?> renderer = compileAndEnhance("helpers.Renderer");

        Method render = renderer.getMethod("render", int.class, long.class, String.class);
        Map<String, Object> locals = (Map<String, Object>) render.invoke(renderer.getDeclaredConstructor().newInstance(), 5, 9L, "hello");
        assertThat(locals).containsEntry("first", 5).containsEntry("second", 9L).containsEntry("label", "hello")
            .containsEntry("local", 6);
        // what action binding reads, through the MethodParameters attribute javac writes with -parameters
        assertThat(Arrays.stream(render.getParameters()).map(Parameter::getName)).containsExactly("first", "second", "label");
        assertThat(LocalVariablesNamesTracer.getLocalVariables()).as("enter and exit balance").isEmpty();
    }

    @Test
    void publicFieldsGetAccessorsAndTheirReadsAndWritesGoThroughThem() throws Exception {
        source("models/Customer.java", """
            package models;
            public class Customer {
                public String name;
                public int age;
                public String readName() { return this.name; }
                public void writeAge(int newAge) { this.age = newAge; }
            }
            """);

        Class<?> customer = compileAndEnhance("models.Customer");

        Object instance = customer.getDeclaredConstructor().newInstance();
        customer.getMethod("setName", String.class).invoke(instance, "Ada");
        assertThat(customer.getMethod("getName").invoke(instance)).isEqualTo("Ada");
        assertThat(customer.getMethod("readName").invoke(instance)).isEqualTo("Ada");
        customer.getMethod("writeAge", int.class).invoke(instance, 7);
        assertThat(customer.getMethod("getAge").invoke(instance)).isEqualTo(7);
    }

    @Test
    void aControllersThreadLocalFieldsAndAMailersStaticMethodsAreRewritten() throws Exception {
        source("controllers/Orders.java", """
            package controllers;
            public class Orders extends play.mvc.Controller {
                public static void show() {
                    renderText(params.get("id"));
                }
            }
            """);
        source("notifiers/Mails.java", """
            package notifiers;
            public class Mails extends play.mvc.Mailer {
                public static void welcome(String to) {
                    addRecipient(to);
                }
            }
            """);

        // ControllersEnhancer reads params through Java.invokeStatic(type, "current"), so each request gets its own
        assertThat(invokes(enhanced("controllers.Orders"), "show")).contains("play/utils/Java.invokeStatic");
        // MailerEnhancer wraps the method in infos.set(...) and infos.remove()
        assertThat(invokes(enhanced("notifiers.Mails"), "welcome")).contains("java/lang/ThreadLocal.set", "java/lang/ThreadLocal.remove");
    }

    @Test
    void aRenderInsideTryWithResourcesClosesTheResourceAndACatchStillCannotSwallowIt() throws Exception {
        // javac compiles try-with-resources' cleanup as a handler that catches Throwable, closes and rethrows; ECJ
        // compiled it as a finally handler. ControllersEnhancer put its Result check at the start of every typed
        // handler, so the RenderText left before close(): each such action leaked its connection, stream or lock.
        source("controllers/Resources.java", """
            package controllers;
            public class Resources extends play.mvc.Controller {
                public static final java.util.List<String> closed = new java.util.ArrayList<>();
                public static void inTryWithResources() throws Exception {
                    try (java.io.Closeable first = () -> closed.add("first"); java.io.Closeable second = () -> closed.add("second")) {
                        renderText("rendered");
                    }
                }
                public static void inTryWithResourcesWithItsOwnCatch() {
                    try (java.io.Closeable own = () -> closed.add("own")) {
                        renderText("rendered");
                    } catch (RuntimeException | java.io.IOException e) {
                        renderText("swallowed");
                    }
                }
                public static void inCatch() {
                    try {
                        renderText("rendered");
                    } catch (Exception e) {
                        renderText("swallowed");
                    }
                }
                public static void inCatchWithAnUnnamedVariable() {
                    try {
                        renderText("rendered");
                    } catch (Exception _) {
                        renderText("swallowed");
                    }
                }
            }
            """);
        Class<?> resources = compileAndEnhance("controllers.Resources");

        assertThat(renders(resources, "inTryWithResources")).isEqualTo("rendered");
        assertThat(String.valueOf(resources.getField("closed").get(null))).as("closed, last opened first").isEqualTo("[second, first]");
        // javac nests the cleanup handler inside the statement's own catch: the first closes, the second keeps its check
        assertThat(renders(resources, "inTryWithResourcesWithItsOwnCatch")).isEqualTo("rendered");
        assertThat(String.valueOf(resources.getField("closed").get(null))).isEqualTo("[second, first, own]");
        assertThat(renders(resources, "inCatch")).isEqualTo("rendered");
        assertThat(renders(resources, "inCatchWithAnUnnamedVariable")).isEqualTo("rendered");
    }

    @Test
    void aCatchOfThrowableThatClosesAndRethrowsOnlySomeKeepsItsCheck() throws Exception {
        // closesAndRethrows took any Throwable handler that closed and then threw what it caught, reading only up to
        // the first athrow: this catch, which rethrows Errors alone, lost its check and swallowed the render. javac's
        // own handler keeps its Throwable in a synthetic local, which the local variable table never names.
        source("controllers/Cleanup.java", """
            package controllers;
            public class Cleanup extends play.mvc.Controller {
                public static void closesThenRethrowsOnlyErrors() {
                    java.io.Closeable resource = () -> {};
                    try {
                        renderText("rendered");
                    } catch (Throwable t) {
                        try { resource.close(); } catch (java.io.IOException e) { }
                        if (t instanceof Error) throw t;
                        renderText("swallowed");
                    }
                }
            }
            """);
        assertThat(renders(compileAndEnhance("controllers.Cleanup"), "closesThenRethrowsOnlyErrors")).isEqualTo("rendered");
    }

    @Test
    void aRenderCaughtByACatchStillRunsTheFinallyAndTheMonitorExitAroundIt() throws Exception {
        // javassist's Handler.insertBefore put the Result check at the end of the method, outside every exception
        // range, so the Result it rethrew left the method at once: no finally of the same try ran, and a catch
        // inside synchronized never reached its monitorexit, for which HotSpot threw IllegalMonitorStateException
        // in place of the render. ECJ's bytecode went through the same code.
        source("controllers/Enclosed.java", """
            package controllers;
            public class Enclosed extends play.mvc.Controller {
                public static final java.util.List<String> log = new java.util.ArrayList<>();
                public static void catchAndFinally() {
                    try {
                        renderText("rendered");
                    } catch (Exception e) {
                        renderText("swallowed");
                    } finally {
                        log.add("finally");
                    }
                }
                public static void catchInsideSynchronized() {
                    synchronized (log) {
                        try {
                            renderText("rendered");
                        } catch (RuntimeException e) {
                            renderText("swallowed");
                        }
                    }
                }
                public static void catchInsideCatch() {
                    try {
                        try {
                            renderText("rendered");
                        } catch (RuntimeException | Error e) {
                            renderText("inner");
                        }
                    } catch (Exception e) {
                        renderText("outer");
                    }
                }
            }
            """);
        Class<?> enclosed = compileAndEnhance("controllers.Enclosed");
        Object log = enclosed.getField("log").get(null);

        assertThat(renders(enclosed, "catchAndFinally")).isEqualTo("rendered");
        assertThat(String.valueOf(log)).as("the finally ran").isEqualTo("[finally]");
        assertThat(renders(enclosed, "catchInsideSynchronized")).isEqualTo("rendered");
        assertThat(Thread.holdsLock(log)).isFalse();
        assertThat(renders(enclosed, "catchInsideCatch")).as("each catch checks").isEqualTo("rendered");
    }

    @Test
    void aPublicFieldAssignedBeforeSuperIsWrittenAsItIsAndLaterWritesGoThroughTheSetter() throws Exception {
        // Java 25 (JEP 513) lets a constructor assign its own fields before super(). PropertiesEnhancer turned that
        // write into FieldAccessor.invokeWriteProperty(this, ...) too, which hands the unconstructed this to a method:
        // the class failed verification ("Type uninitializedThis ... is not assignable to java/lang/Object").
        source("models/Early.java", """
            package models;
            public class Early {
                public String code;
                public String trail = "";
                public Early(String value) {
                    code = value.trim();
                    super();
                    code = code + "!";
                }
                public void setCode(String code) {
                    trail += "set;";
                    this.code = code;
                }
            }
            """);
        Class<?> early = compileAndEnhance("models.Early");
        Object instance = early.getConstructor(String.class).newInstance(" x ");
        assertThat(early.getField("code").get(instance)).isEqualTo("x!");
        assertThat(early.getField("trail").get(instance)).as("the write after super() only").isEqualTo("set;");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anIncrementAtAVariablesFirstInstructionAndAWideIncrementAreTracked() throws Exception {
        // The scan for stores began after the instruction at the entry's start_pc, so an iinc there went unseen, and
        // it knew no wide prefix, which iinc takes for an increment outside -128..127. Both compilers emit both.
        source("helpers/Counter.java", """
            package helpers;
            import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer;
            public class Counter implements play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesSupport {
                public java.util.Map<String, Object> count() {
                    int count = 0;
                    count++;
                    int total = 0;
                    String marker = "m";
                    total += 1000;
                    return new java.util.HashMap<>(LocalVariablesNamesTracer.getLocalVariables());
                }
            }
            """);
        Class<?> counter = compileAndEnhance("helpers.Counter");
        Map<String, Object> locals = (Map<String, Object>) counter.getMethod("count").invoke(counter.getDeclaredConstructor().newInstance());
        assertThat(locals).containsEntry("count", 1).containsEntry("total", 1000).containsEntry("marker", "m");
    }

    /** The text of the RenderText the action throws, as an action call that is allowed to render. */
    private static String renders(Class<?> controller, String action) throws Exception {
        ControllersEnhancer.ControllerInstrumentation.initActionCall();
        try {
            controller.getMethod(action).invoke(null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof play.mvc.results.RenderText text) {
                java.lang.reflect.Field field = play.mvc.results.RenderText.class.getDeclaredField("text");
                field.setAccessible(true);
                return (String) field.get(text);
            }
            throw e;
        } finally {
            ControllersEnhancer.ControllerInstrumentation.stopActionCall();
        }
        throw new AssertionError(action + " rendered nothing");
    }

    @Test
    void theSignatureChecksumChangesWithASignatureOrALocalVariableNameAndNotWithABody() throws Exception {
        // A hot swap is tried only while the checksum holds; a checksum that moved with every compile
        // would restart the application on every change.
        String template = """
            package controllers;
            public class Pages {
                public static String show(String id) {
                    String %s = "%s" + id;
                    return %s;
                }
            }
            """;
        int first = signature(template.formatted("title", "one", "title"));
        assertThat(signature(template.formatted("title", "one", "title"))).as("the same source again").isEqualTo(first);
        assertThat(signature(template.formatted("title", "two", "title"))).as("a body change").isEqualTo(first);
        assertThat(signature(template.formatted("heading", "one", "heading"))).as("a local renamed").isNotEqualTo(first);
    }

    @Test
    void theSignatureChecksumChangesWithAStringConcatenatedInTheStaticInitializer() throws Exception {
        // A hot swap keeps the static values of the old class, so a changed static initializer needs a restart.
        // "Hello " + x compiles to invokedynamic makeConcatWithConstants, with the literal in a bootstrap argument
        // and no ldc, which was all the checksum read: an edited literal was swapped in and never took effect.
        String template = """
            package controllers;
            public class Pages {
                public static final String GREETING = "%s" + System.getProperty("java.version");
            }
            """;
        assertThat(signature(template.formatted("Hello "))).isNotEqualTo(signature(template.formatted("Hi ")));
    }

    private int signature(String source) throws Exception {
        Play.classes = new ApplicationClasses();
        source("controllers/Pages.java", source);
        Play.classes.compiler().compile(new String[] { "controllers.Pages" });
        ApplicationClass pages = Play.classes.getApplicationClass("controllers.Pages");
        pages.enhance();
        return pages.sigChecksum;
    }

    private byte[] enhanced(String name) {
        Play.classes.compiler().compile(new String[] { name });
        ApplicationClass compiled = Play.classes.getApplicationClass(name);
        compiled.enhance();
        return compiled.enhancedByteCode;
    }

    /** The methods a method calls, as owner.name. */
    private static List<String> invokes(byte[] bytecode, String method) {
        return ClassFile.of().parse(bytecode).methods().stream()
            .filter(m -> m.methodName().equalsString(method))
            .flatMap(m -> m.code().orElseThrow().elementStream())
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast)
            .map(call -> call.owner().asInternalName() + "." + call.name().stringValue())
            .toList();
    }

    private Class<?> compileAndEnhance(String name) throws Exception {
        Play.classes.compiler().compile(new String[] { name });
        ApplicationClass compiled = Play.classes.getApplicationClass(name);
        compiled.enhance();
        assertThat(compiled.enhancedByteCode).as("enhanced").isNotSameAs(compiled.javaByteCode);
        return new ByteLoader().define(name, compiled.enhancedByteCode);
    }

    private void source(String path, String content) throws IOException {
        File file = new File(app, "app/" + path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader() {
            super(JavacEnhancersTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
