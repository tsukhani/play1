package play.classloading.enhancers;

import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.Opcode;
import javassist.bytecode.annotation.Annotation;
import play.classloading.ApplicationClasses.ApplicationClass;

/**
 * Compute a unique hash for the class signature.
 */
public class SigEnhancer extends Enhancer {

    @Override
    public void enhanceThisClass(ApplicationClass applicationClass) throws Exception {
        if (isScala(applicationClass)) {
            return;
        }

        CtClass ctClass = makeClass(applicationClass);
        if (isScalaObject(ctClass)) {
            return;
        }

        StringBuilder sigChecksum = new StringBuilder();

        sigChecksum.append("Class->").append(ctClass.getName()).append(":");
        for (Annotation annotation : getAnnotations(ctClass).getAnnotations()) {
            sigChecksum.append(annotation).append(",");
        }

        for (CtField field : ctClass.getDeclaredFields()) {
            sigChecksum.append(" Field->").append(ctClass.getName()).append(" ").append(field.getSignature()).append(":");
            sigChecksum.append(field.getSignature());
            for (Annotation annotation : getAnnotations(field).getAnnotations()) {
                sigChecksum.append(annotation).append(",");
            }
        }

        for (CtMethod method : ctClass.getDeclaredMethods()) {
            sigChecksum.append(" Method->").append(method.getName()).append(method.getSignature()).append(":");
            for (Annotation annotation : getAnnotations(method).getAnnotations()) {
                sigChecksum.append(annotation).append(" ");
            }
            // Signatures names
            CodeAttribute codeAttribute = (CodeAttribute) method.getMethodInfo().getAttribute("Code");
            if (codeAttribute == null || javassist.Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            LocalVariableAttribute localVariableAttribute = (LocalVariableAttribute) codeAttribute.getAttribute("LocalVariableTable");
            if (localVariableAttribute != null) {
                for (int i = 0; i < localVariableAttribute.tableLength(); i++) {
                    sigChecksum.append(localVariableAttribute.variableName(i)).append(",");
                }
            }
        }

        if (ctClass.getClassInitializer() != null) {
            sigChecksum.append("Static Code->");
            for (CodeIterator i = ctClass.getClassInitializer().getMethodInfo().getCodeAttribute().iterator(); i.hasNext();) {
                int index = i.next();
                int op = i.byteAt(index);
                sigChecksum.append(op);
                // Audit M12: handle both LDC (1-byte operand) and LDC_W (2-byte
                // operand). Java 25's compiler emits LDC_W for any constant whose
                // pool index exceeds 255, which is increasingly common as classes
                // grow. Reading byteAt(index+1) for an LDC_W instruction returns
                // the high byte of the 2-byte index — wrong constant lookup —
                // producing a sigChecksum that varies on every recompile, which
                // surfaces as spurious RestartNeededException on every reload.
                if (op == Opcode.LDC) {
                    sigChecksum.append("[").append(i.get().getConstPool().getLdcValue(i.byteAt(index + 1))).append("]");
                } else if (op == Opcode.LDC_W || op == Opcode.LDC2_W) {
                    int poolIdx = (i.byteAt(index + 1) << 8) | i.byteAt(index + 2);
                    sigChecksum.append("[").append(i.get().getConstPool().getLdcValue(poolIdx)).append("]");
                }
                sigChecksum.append(".");
            }
        }

        if (ctClass.getName().endsWith("$")) {
            sigChecksum.append("Singletons->");
            for (CodeIterator i = ctClass.getDeclaredConstructors()[0].getMethodInfo().getCodeAttribute().iterator(); i.hasNext();) {
                int index = i.next();
                int op = i.byteAt(index);
                sigChecksum.append(op);
                // Audit M12: handle both LDC (1-byte operand) and LDC_W (2-byte
                // operand). Java 25's compiler emits LDC_W for any constant whose
                // pool index exceeds 255, which is increasingly common as classes
                // grow. Reading byteAt(index+1) for an LDC_W instruction returns
                // the high byte of the 2-byte index — wrong constant lookup —
                // producing a sigChecksum that varies on every recompile, which
                // surfaces as spurious RestartNeededException on every reload.
                if (op == Opcode.LDC) {
                    sigChecksum.append("[").append(i.get().getConstPool().getLdcValue(i.byteAt(index + 1))).append("]");
                } else if (op == Opcode.LDC_W || op == Opcode.LDC2_W) {
                    int poolIdx = (i.byteAt(index + 1) << 8) | i.byteAt(index + 2);
                    sigChecksum.append("[").append(i.get().getConstPool().getLdcValue(poolIdx)).append("]");
                }
                sigChecksum.append(".");
            }
        }

        // Done.
        applicationClass.sigChecksum = sigChecksum.toString().hashCode();
    }
}
