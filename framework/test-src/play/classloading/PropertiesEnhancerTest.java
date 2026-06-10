package play.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Properties;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.PropertiesEnhancer;
import play.classloading.enhancers.PropertiesEnhancer.PlayPropertyAccessor;
import play.vfs.VirtualFile;

/**
 * Behavioral tests for {@link PropertiesEnhancer}: enhance real compiled fixture bytecode,
 * load the enhanced class, and exercise the generated accessors + the rewritten field-access
 * call sites. (PF-136)
 */
class PropertiesEnhancerTest {

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
    }

    /** Read the real compiled bytes of a top-level fixture class off the test classpath. */
    private static byte[] classBytes(Class<?> c) throws Exception {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    /** Build an ApplicationClass whose enhancedByteCode is the fixture's compiled bytes. */
    private static ApplicationClass appClassFor(Class<?> fixture) throws Exception {
        ApplicationClass ac = new ApplicationClass();
        ac.name = fixture.getName();
        // isScala() reads javaFile.getName(): must be non-null and NOT end in .scala.
        ac.javaFile = VirtualFile.open(fixture.getName().replace('.', '/') + ".java");
        ac.javaByteCode = classBytes(fixture);
        ac.enhancedByteCode = ac.javaByteCode;
        // Enhancer.ApplicationClassesClasspath.openClassfile consults Play.classes.
        Play.classes.add(ac);
        return ac;
    }

    /** Minimal classloader that defines exactly the enhanced bytecode under test. */
    private static final class ByteLoader extends ClassLoader {
        ByteLoader() {
            super(PropertiesEnhancerTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    void generatesWorkingGettersAndSettersForPublicFields() throws Exception {
        ApplicationClass ac = appClassFor(PropertiesEnhancerFixture.class);

        new PropertiesEnhancer().enhanceThisClass(ac);

        Class<?> enhanced = new ByteLoader().define(ac.name, ac.enhancedByteCode);
        Object instance = enhanced.getDeclaredConstructor().newInstance();

        // Generated accessors exist...
        Method getName = enhanced.getMethod("getName");
        Method setName = enhanced.getMethod("setName", String.class);
        Method getAge = enhanced.getMethod("getAge");
        Method setAge = enhanced.getMethod("setAge", int.class);

        // ...and round-trip through the real field.
        setName.invoke(instance, "Ada");
        assertEquals("Ada", getName.invoke(instance), "generated setName/getName must round-trip");
        setAge.invoke(instance, 42);
        assertEquals(42, getAge.invoke(instance), "generated setAge/getAge must round-trip");

        // The public field itself is still reachable and reflects the setter write.
        assertEquals("Ada", enhanced.getField("name").get(instance), "public field must mirror generated setter");

        // Generated accessors carry the @PlayPropertyAccessor marker the enhancer stamps on them.
        assertTrue(getName.isAnnotationPresent(PlayPropertyAccessor.class),
                "generated getter must be annotated @PlayPropertyAccessor");
        assertTrue(setAge.isAnnotationPresent(PlayPropertyAccessor.class),
                "generated setter must be annotated @PlayPropertyAccessor");
    }

    @Test
    void rewritesDirectFieldAccessThroughFieldAccessor() throws Exception {
        ApplicationClass ac = appClassFor(PropertiesEnhancerFixture.class);

        new PropertiesEnhancer().enhanceThisClass(ac);

        // Structural proof: the rewritten methods now reference FieldAccessor.invoke{Read,Write}Property.
        assertTrue(methodReferences(ac.enhancedByteCode, "readNameDirect", "invokeReadProperty"),
                "enhancer must rewrite 'return this.name' to call FieldAccessor.invokeReadProperty");
        assertTrue(methodReferences(ac.enhancedByteCode, "writeAgeDirect", "invokeWriteProperty"),
                "enhancer must rewrite 'this.age = ...' to call FieldAccessor.invokeWriteProperty");

        // Functional proof: the rewritten call sites still produce correct values at runtime.
        Class<?> enhanced = new ByteLoader().define(ac.name, ac.enhancedByteCode);
        Object instance = enhanced.getDeclaredConstructor().newInstance();

        enhanced.getMethod("writeAgeDirect", int.class).invoke(instance, 7);
        assertEquals(7, enhanced.getMethod("readAgeDirect").invoke(instance),
                "rewritten write+read of 'age' must round-trip");

        enhanced.getField("name").set(instance, "Grace");
        assertEquals("Grace", enhanced.getMethod("readNameDirect").invoke(instance),
                "rewritten read of 'name' must return the field value");
    }

    @Test
    void unenhancedFixtureHasNoAccessorsOrRewrite() throws Exception {
        // Mutation check: against the UN-enhanced bytecode the accessors and the FieldAccessor
        // rewrite are both absent, so the tests above can only pass because the enhancer ran.
        byte[] raw = classBytes(PropertiesEnhancerFixture.class);

        Class<?> rawClass = new ByteLoader().define(PropertiesEnhancerFixture.class.getName(), raw);
        boolean hasGetter = true;
        try {
            rawClass.getMethod("getName");
        } catch (NoSuchMethodException e) {
            hasGetter = false;
        }
        assertFalse(hasGetter, "un-enhanced fixture must NOT already have a generated getter");

        assertFalse(methodReferences(raw, "readNameDirect", "invokeReadProperty"),
                "un-enhanced fixture must NOT reference FieldAccessor");
    }

    /** True if the given method in the bytecode references the named callee (via javassist). */
    private static boolean methodReferences(byte[] bytecode, String methodName, String calleeNeedle) throws Exception {
        ClassPool pool = new ClassPool();
        pool.appendSystemPath();
        CtClass ct = pool.makeClass(new ByteArrayInputStream(bytecode));
        try {
            CtMethod m = ct.getDeclaredMethod(methodName);
            final boolean[] found = { false };
            m.instrument(new javassist.expr.ExprEditor() {
                @Override
                public void edit(javassist.expr.MethodCall call) {
                    if (call.getMethodName().equals(calleeNeedle)) {
                        found[0] = true;
                    }
                }
            });
            return found[0];
        } finally {
            ct.detach();
        }
    }
}
