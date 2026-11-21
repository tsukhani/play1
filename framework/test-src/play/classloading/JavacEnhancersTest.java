package play.classloading;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
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

    private int signature(String source) throws Exception {
        Play.classes = new ApplicationClasses();
        source("controllers/Pages.java", source);
        Play.classes.compiler().compile(new String[] { "controllers.Pages" });
        ApplicationClass pages = Play.classes.getApplicationClass("controllers.Pages");
        pages.enhance();
        return pages.sigChecksum;
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
