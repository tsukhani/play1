package play.classloading.enhancers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javassist.bytecode.AttributeInfo;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.TypeAnnotationsAttribute;

/**
 * Drops the Code-level type annotations of every method an enhancer moved (PF-187).
 *
 * A {@code TYPE_USE} annotation on a local variable, a cast, an {@code instanceof} or a
 * {@code new} is stored in the method's Code attribute as
 * {@code Runtime(In)VisibleTypeAnnotations} with bytecode offsets. Javassist's
 * {@code CodeIterator} gap insertion shifts the exception table, the line numbers, the
 * local variable tables and the stack maps, but has no shift for that attribute, so after
 * an enhancer grows a method its type annotations describe the wrong bytes. The JVM and
 * core reflection never read them (locals and expressions have no reflective view), but a
 * tool that validates them, ProGuard among them, rejects the class.
 *
 * The pass runs once per class after every enhancer, plugins' included. Javassist gaps
 * only grow code, so a method whose code length is unchanged had no offset moved and keeps
 * its annotations. Type annotations on the method itself, on fields and on the class are
 * never touched: {@code Method.getAnnotatedReturnType()} and friends read those.
 */
public final class CodeTypeAnnotations {

    private CodeTypeAnnotations() {
    }

    /**
     * @param original
     *            the bytes the compiler produced
     * @param enhanced
     *            the bytes after the enhancers ran
     * @return {@code enhanced} itself when no annotated method moved, else a copy with the
     *         Code-level type annotations removed from every method whose code grew
     */
    public static byte[] dropWhereCodeMoved(byte[] original, byte[] enhanced) throws IOException {
        if (enhanced == original) {
            return enhanced;
        }
        ClassFile classFile = read(enhanced);
        List<MethodInfo> annotated = new ArrayList<>();
        for (MethodInfo method : classFile.getMethods()) {
            CodeAttribute code = method.getCodeAttribute();
            if (code != null && code.getAttributes().stream().anyMatch(CodeTypeAnnotations::isTypeAnnotations)) {
                annotated.add(method);
            }
        }
        if (annotated.isEmpty()) {
            return enhanced; // nearly every class: one parse, no write
        }
        Map<String, Integer> lengthsBefore = new HashMap<>();
        for (MethodInfo method : read(original).getMethods()) {
            CodeAttribute code = method.getCodeAttribute();
            if (code != null) {
                lengthsBefore.put(method.getName() + method.getDescriptor(), code.getCodeLength());
            }
        }
        boolean changed = false;
        for (MethodInfo method : annotated) {
            CodeAttribute code = method.getCodeAttribute();
            if (Objects.equals(lengthsBefore.get(method.getName() + method.getDescriptor()), code.getCodeLength())) {
                continue;
            }
            // CodeAttribute.getAttributes() is the live list, so removing here edits the class.
            changed |= code.getAttributes().removeIf(CodeTypeAnnotations::isTypeAnnotations);
        }
        return changed ? write(classFile) : enhanced;
    }

    private static boolean isTypeAnnotations(AttributeInfo attribute) {
        return attribute instanceof TypeAnnotationsAttribute;
    }

    private static ClassFile read(byte[] bytes) throws IOException {
        return new ClassFile(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static byte[] write(ClassFile classFile) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        classFile.write(new DataOutputStream(out));
        return out.toByteArray();
    }
}
