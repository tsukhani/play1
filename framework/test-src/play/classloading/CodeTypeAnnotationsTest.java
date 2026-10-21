package play.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LocalVariableInfo;
import java.lang.classfile.attribute.LocalVariableTableAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.CodeTypeAnnotations;
import play.classloading.enhancers.PropertiesEnhancer;
import play.plugins.PluginCollection;
import play.vfs.VirtualFile;

/**
 * PF-187: an enhancer that grows a method through Javassist's gap insertion leaves the
 * Code attribute's {@code RuntimeVisibleTypeAnnotations} at their pre-enhancement offsets,
 * and ProGuard rejects the class ("Invalid instruction offset"). The pass at the end of
 * {@link ApplicationClass#enhance()} drops the Code-level type annotations of every method
 * whose code moved and leaves everything else alone.
 *
 * The fixture is enhanced through {@code ApplicationClass.enhance()} with a plugin collection
 * that runs the real {@link PropertiesEnhancer}, so the test covers the production path and
 * not only the helper. The checks read the bytes with the JDK's ClassFile API.
 */
class CodeTypeAnnotationsTest {

    private ApplicationClasses savedClasses;
    private Properties savedConfig;
    private boolean savedUsePrecompiled;
    private PluginCollection savedPlugins;

    @BeforeEach
    void setUp() {
        savedClasses = Play.classes;
        savedConfig = Play.configuration;
        savedUsePrecompiled = Play.usePrecompiled;
        savedPlugins = Play.pluginCollection;

        Play.configuration = new Properties();
        Play.classes = new ApplicationClasses();
        Play.usePrecompiled = false;
        Play.pluginCollection = new PluginCollection() {
            {
                addPlugin(new PlayPlugin() {
                    @Override
                    public void enhance(ApplicationClass applicationClass) throws Exception {
                        new PropertiesEnhancer().enhanceThisClass(applicationClass);
                    }
                });
            }
        };
    }

    @AfterEach
    void tearDown() {
        Play.classes = savedClasses;
        Play.configuration = savedConfig;
        Play.usePrecompiled = savedUsePrecompiled;
        Play.pluginCollection = savedPlugins;
    }

    @Test
    void dropsCodeTypeAnnotationsOfAMethodTheEnhancersMoved() throws Exception {
        ApplicationClass ac = appClassFor(CodeTypeAnnotationsFixture.class);

        ac.enhance();

        assertTrue(code(ac.enhancedByteCode, "moved").codeLength() > code(ac.javaByteCode, "moved").codeLength(),
                "PropertiesEnhancer must grow moved() (it reads another class's public field), or this test exercises nothing");

        for (MethodModel m : ClassFile.of().parse(ac.enhancedByteCode).methods()) {
            CodeAttribute code = m.findAttribute(Attributes.code()).orElse(null);
            if (code == null) {
                continue;
            }
            Set<Integer> boundaries = instructionBoundaries(code);
            List<LocalVariableInfo> locals = code.findAttribute(Attributes.localVariableTable())
                    .map(LocalVariableTableAttribute::localVariables).orElse(List.of());
            for (TypeAnnotation annotation : codeTypeAnnotations(code)) {
                if (!(annotation.targetInfo() instanceof TypeAnnotation.LocalVarTarget target)) {
                    continue;
                }
                for (TypeAnnotation.LocalVarTargetInfo range : target.table()) {
                    int start = code.labelToBci(range.startLabel());
                    int end = code.labelToBci(range.endLabel());
                    String where = m.methodName().stringValue() + " localvar_target [" + start + ", " + end + ") slot " + range.index();
                    assertTrue(boundaries.contains(start) && boundaries.contains(end),
                            where + " must start and end on an instruction boundary");
                    assertTrue(locals.stream().anyMatch(l -> l.slot() == range.index()
                                    && l.startPc() == start && l.startPc() + l.length() == end),
                            where + " must describe the same range as the LocalVariableTable entry for that slot");
                }
            }
        }
    }

    @Test
    void keepsCodeTypeAnnotationsOfAMethodTheEnhancersLeftAlone() throws Exception {
        ApplicationClass ac = appClassFor(CodeTypeAnnotationsFixture.class);

        ac.enhance();

        List<TypeAnnotation.TargetType> before = targetTypes(code(ac.javaByteCode, "untouched"));
        assertFalse(before.isEmpty(), "javac must have written Code-level type annotations for untouched()");
        assertEquals(before, targetTypes(code(ac.enhancedByteCode, "untouched")),
                "a method whose code did not move must keep its Code-level type annotations");
    }

    @Test
    void keepsTypeAnnotationsOnTheMethodItself() throws Exception {
        ApplicationClass ac = appClassFor(CodeTypeAnnotationsFixture.class);

        ac.enhance();

        Class<?> enhanced = new ByteLoader().define(ac.name, ac.enhancedByteCode);
        Method annotatedReturn = enhanced.getMethod("annotatedReturn");
        assertTrue(annotatedReturn.getAnnotatedReturnType().isAnnotationPresent(CodeTypeAnnotationsFixture.Nullable.class),
                "a return-type annotation lives on method_info, not in Code, and must survive");

        // The moved method still verifies and runs after the drop.
        Object instance = enhanced.getDeclaredConstructor().newInstance();
        PropertiesEnhancerFixture other = new PropertiesEnhancerFixture();
        other.name = " Ada ";
        assertEquals("AdaAda", enhanced.getMethod("moved", PropertiesEnhancerFixture.class).invoke(instance, other));
    }

    @Test
    void returnsTheEnhancedBytesUntouchedWhenNoCodeMoved() throws Exception {
        byte[] original = classBytes(CodeTypeAnnotationsFixture.class);
        byte[] enhanced = original.clone();

        assertSame(enhanced, CodeTypeAnnotations.dropWhereCodeMoved(original, enhanced),
                "a class whose methods kept their length must not be rewritten");
    }

    // ---- helpers ----

    private static byte[] classBytes(Class<?> c) throws Exception {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    private static ApplicationClass appClassFor(Class<?> fixture) throws Exception {
        ApplicationClass ac = new ApplicationClass();
        ac.name = fixture.getName();
        // isScala() reads javaFile.getName(): must be non-null and NOT end in .scala.
        ac.javaFile = VirtualFile.open(fixture.getName().replace('.', '/') + ".java");
        ac.javaByteCode = classBytes(fixture);
        ac.enhancedByteCode = ac.javaByteCode;
        Play.classes.add(ac);
        return ac;
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader() {
            super(CodeTypeAnnotationsTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static CodeAttribute code(byte[] bytes, String methodName) {
        for (MethodModel m : ClassFile.of().parse(bytes).methods()) {
            if (m.methodName().equalsString(methodName)) {
                return m.findAttribute(Attributes.code()).orElseThrow();
            }
        }
        throw new AssertionError("no method " + methodName);
    }

    /** Offsets at which an instruction begins, plus the code length, where a range may end. */
    private static Set<Integer> instructionBoundaries(CodeAttribute code) {
        Set<Integer> boundaries = new HashSet<>();
        int pos = 0;
        for (CodeElement element : code) {
            if (element instanceof Instruction instruction) {
                boundaries.add(pos);
                pos += instruction.sizeInBytes();
            }
        }
        boundaries.add(pos);
        return boundaries;
    }

    private static List<TypeAnnotation> codeTypeAnnotations(CodeAttribute code) {
        return code.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
                .map(RuntimeVisibleTypeAnnotationsAttribute::annotations).orElse(List.of());
    }

    private static List<TypeAnnotation.TargetType> targetTypes(CodeAttribute code) {
        List<TypeAnnotation.TargetType> types = new ArrayList<>();
        for (TypeAnnotation annotation : codeTypeAnnotations(code)) {
            types.add(annotation.targetInfo().targetType());
        }
        return types;
    }
}
