package play.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.LocalvariablesNamesEnhancer;
import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer;
import play.vfs.VirtualFile;

/**
 * Behavioral tests for {@link LocalvariablesNamesEnhancer}: enhance real compiled fixture
 * bytecode (compiled with debug info so the LocalVariableTable survives), load the enhanced
 * class, run an enhanced method, and assert the method/parameter names are recoverable from
 * the tracer registry the enhancer installs. (PF-136)
 */
class LocalvariablesNamesEnhancerTest {

    private ApplicationClasses savedClasses;
    private Properties savedConfig;
    private boolean savedUsePrecompiled;

    @BeforeEach
    void setUp() {
        savedClasses = Play.classes;
        savedConfig = Play.configuration;
        savedUsePrecompiled = Play.usePrecompiled;

        Play.configuration = new Properties();
        Play.classes = new ApplicationClasses();
        Play.usePrecompiled = false;
    }

    @AfterEach
    void tearDown() {
        Play.classes = savedClasses;
        Play.configuration = savedConfig;
        Play.usePrecompiled = savedUsePrecompiled;
        // The enhanced method wraps its body in tracer enter()/exit(); clear any residue so
        // we never leak ThreadLocal state into other tests sharing this thread.
        LocalVariablesNamesTracer.clear();
    }

    private static byte[] classBytes(Class<?> c) throws Exception {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    private static ApplicationClass appClassFor(Class<?> fixture) throws Exception {
        ApplicationClass ac = new ApplicationClass();
        ac.name = fixture.getName();
        ac.javaFile = VirtualFile.open(fixture.getName().replace('.', '/') + ".java");
        ac.javaByteCode = classBytes(fixture);
        ac.enhancedByteCode = ac.javaByteCode;
        Play.classes.add(ac);
        return ac;
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader() {
            super(LocalvariablesNamesEnhancerTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsParameterNamesInTracerRegistry() throws Exception {
        ApplicationClass ac = appClassFor(LocalVariablesFixture.class);

        new LocalvariablesNamesEnhancer().enhanceThisClass(ac);

        Class<?> enhanced = new ByteLoader().define(ac.name, ac.enhancedByteCode);
        Object instance = enhanced.getDeclaredConstructor().newInstance();

        // The fixture method returns LocalVariablesNamesTracer.getLocalVariables() captured
        // from INSIDE its own (enhanced) body — i.e. the names/values the enhancer recorded.
        Map<String, Object> locals = (Map<String, Object>) enhanced
                .getMethod("sumAndCaptureLocals", int.class, long.class, String.class)
                .invoke(instance, 5, 9L, "hello");

        // Parameter names — normally erased from bytecode — are recoverable by name.
        assertTrue(locals.containsKey("first"), "param name 'first' must be tracked by the enhancer");
        assertTrue(locals.containsKey("second"), "param name 'second' must be tracked by the enhancer");
        assertTrue(locals.containsKey("label"), "param name 'label' must be tracked by the enhancer");

        // And the tracked values match the actual call arguments + a local.
        assertEquals(5, ((Number) locals.get("first")).intValue(), "tracked 'first' must equal the argument");
        assertEquals(9L, ((Number) locals.get("second")).longValue(), "tracked 'second' must equal the argument");
        assertEquals("hello", locals.get("label"), "tracked 'label' must equal the argument");
        assertTrue(locals.containsKey("local"), "the in-body local variable name must also be tracked");
    }

    @Test
    void tracerExitBalancesEnterSoNoStateLeaks() throws Exception {
        ApplicationClass ac = appClassFor(LocalVariablesFixture.class);
        new LocalvariablesNamesEnhancer().enhanceThisClass(ac);

        Class<?> enhanced = new ByteLoader().define(ac.name, ac.enhancedByteCode);
        Object instance = enhanced.getDeclaredConstructor().newInstance();

        enhanced.getMethod("sumAndCaptureLocals", int.class, long.class, String.class)
                .invoke(instance, 1, 2L, "x");

        // enhancer inserts a matching exit() (insertAfter ... asFinally=true) so after the call
        // returns the per-frame map has been popped — locals() is empty again on this thread.
        assertTrue(LocalVariablesNamesTracer.getLocalVariables().isEmpty(),
                "tracer enter()/exit() must balance: no frame left on the stack after the call returns");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unenhancedFixtureTracksNothing() throws Exception {
        // Mutation check: without enhancement the method body has no enter()/addVariable calls,
        // so getLocalVariables() returns the empty fallback map — proving the assertions above
        // can only pass because the enhancer instrumented the method.
        byte[] raw = classBytes(LocalVariablesFixture.class);
        Class<?> rawClass = new ByteLoader().define(LocalVariablesFixture.class.getName(), raw);
        Object instance = rawClass.getDeclaredConstructor().newInstance();

        Map<String, Object> locals = (Map<String, Object>) rawClass
                .getMethod("sumAndCaptureLocals", int.class, long.class, String.class)
                .invoke(instance, 5, 9L, "hello");

        assertFalse(locals.containsKey("first"), "un-enhanced method must NOT track parameter names");
        assertTrue(locals.isEmpty(), "un-enhanced method captures an empty tracer map");
    }
}
