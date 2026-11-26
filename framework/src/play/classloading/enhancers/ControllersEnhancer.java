package play.classloading.enhancers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Comparator;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;
import javassist.bytecode.annotation.Annotation;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.Handler;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.UnexpectedException;
import play.utils.PlayThreadLocal;

/**
 * Enhance controllers classes. 
 */
public class ControllersEnhancer extends Enhancer {

    public static final ThreadLocal<Stack<String>> currentAction = new PlayThreadLocal<>();

    @Override
    public void enhanceThisClass(final ApplicationClass applicationClass) throws Exception {
        if (isAnon(applicationClass)) {
            return;
        }

        CtClass ctClass = makeClass(applicationClass);

        if (!ctClass.subtypeOf(classPool.get(ControllerSupport.class.getName()))) {
            return;
        }

        for (final CtMethod ctMethod : ctClass.getDeclaredMethods()) {

            // Threaded access
            ctMethod.instrument(new ExprEditor() {

                @Override
                public void edit(FieldAccess fieldAccess) throws CannotCompileException {
                    try {
                        if (isThreadedFieldAccess(fieldAccess.getField())) {
                            if (fieldAccess.isReader()) {
                                fieldAccess.replace("$_ = ($r)play.utils.Java.invokeStatic($type, \"current\");");
                            }
                        }
                    } catch (Exception e) {
                        Logger.error(e, "Error in ControllersEnhancer. %s.%s has not been properly enhanced (fieldAccess %s).", applicationClass.name, ctMethod.getName(), fieldAccess);
                        throw new UnexpectedException(e);
                    }
                }
            });

            // Auto-redirect
            boolean isHandler = false;
            for (Annotation a : getAnnotations(ctMethod).getAnnotations()) {
                if (a.getTypeName().startsWith("play.mvc.")) {
                    isHandler = true;
                    break;
                }
                if (a.getTypeName().endsWith("$ByPass")) {
                    isHandler = true;
                    break;
                }
            }

            // Perhaps it is a scala-generated method ?
            if (ctMethod.getName().contains("$")) {
                isHandler = true;
            } else {
                if (ctClass.getName().endsWith("$") && ctMethod.getParameterTypes().length == 0) {
                    try {
                        ctClass.getField(ctMethod.getName());
                        isHandler = true;
                    } catch (NotFoundException e) {
                        // ok
                    }
                }
            }

            if (isScalaObject(ctClass)) {

                // Auto reverse -->
                if (Modifier.isPublic(ctMethod.getModifiers()) && ((ctClass.getName().endsWith("$") && !ctMethod.getName().contains("$default$"))) && !isHandler) {
                    try {
                        ctMethod.insertBefore(
                                "if(play.mvc.Controller._currentReverse.get() != null) {"
                                + "play.mvc.Controller.redirect(\"" + ctClass.getName().replace("$", "") + "." + ctMethod.getName() + "\", $args);"
                                + generateValidReturnStatement(ctMethod.getReturnType())
                                + "}");

                        ctMethod.insertBefore(
                                "((java.util.Stack)play.classloading.enhancers.ControllersEnhancer.currentAction.get()).push(\"" + ctClass.getName().replace("$", "") + "." + ctMethod.getName() + "\");");

                        ctMethod.insertAfter(
                                "((java.util.Stack)play.classloading.enhancers.ControllersEnhancer.currentAction.get()).pop();", true);

                    } catch (Exception e) {
                        Logger.error(e, "Error in ControllersEnhancer. %s.%s has not been properly enhanced (auto-reverse).", applicationClass.name, ctMethod.getName());
                        throw new UnexpectedException(e);
                    }
                }

            } else {

                // Auto redirect -->
                if (Modifier.isPublic(ctMethod.getModifiers()) && Modifier.isStatic(ctMethod.getModifiers()) && ctMethod.getReturnType().equals(CtClass.voidType) && !isHandler) {
                    try {
                        ctMethod.insertBefore(
                                "if(!play.classloading.enhancers.ControllersEnhancer.ControllerInstrumentation.isActionCallAllowed()) {"
                                + "play.mvc.Controller.redirect(\"" + ctClass.getName().replace("$", "") + "." + ctMethod.getName() + "\", $args);"
                                + generateValidReturnStatement(ctMethod.getReturnType()) + "}"
                                + "play.classloading.enhancers.ControllersEnhancer.ControllerInstrumentation.stopActionCall();");

                    } catch (Exception e) {
                        Logger.error(e, "Error in ControllersEnhancer. %s.%s has not been properly enhanced (auto-redirect).", applicationClass.name, ctMethod.getName());
                        throw new UnexpectedException(e);
                    }
                }

            }

            // Enhance global catch to avoid potential unwanted catching of play.mvc.results.Result
            final Set<Integer> checkedHandlers = new TreeSet<>(Comparator.reverseOrder());
            ctMethod.instrument(new ExprEditor() {

                @Override
                public void edit(Handler handler) {
                    if (!handler.isFinally() && !closesAndRethrows(handler)) {
                        checkedHandlers.add(handler.indexOfBytecode());
                    }
                }
            });
            insertResultChecks(ctMethod, checkedHandlers);

        }

        // Done.
        applicationClass.enhancedByteCode = ctClass.toBytecode();

        ctClass.defrost();

    }

    /**
     * Puts {@code if (e instanceof Result || e instanceof Suspend) throw e;} at the start of each handler, in the
     * handler's own place. javassist's Handler.insertBefore appended it at the end of the method instead, and code
     * there is in no exception range: the Result it rethrew skipped every finally and monitorexit around the catch
     * (HotSpot then threw IllegalMonitorStateException in place of the render), with ECJ's bytecode too. In place,
     * the ranges that cover the handler's code cover the check as well. The check works on the caught throwable on
     * the stack and needs no local.
     *
     * @param handlerPcs
     *            the handlers' positions, highest first, so that an insertion never moves one still to come
     */
    private void insertResultChecks(CtMethod method, Set<Integer> handlerPcs) throws BadBytecode {
        if (handlerPcs.isEmpty()) {
            return;
        }
        MethodInfo info = method.getMethodInfo();
        ConstPool constPool = info.getConstPool();
        int result = constPool.addClassInfo("play.mvc.results.Result");
        int suspend = constPool.addClassInfo("play.Invoker$Suspend");
        byte[] check = {
            (byte) Opcode.DUP,
            (byte) Opcode.INSTANCEOF, (byte) (result >> 8), (byte) result,
            (byte) Opcode.IFNE, 0, 10, // to the athrow
            (byte) Opcode.DUP,
            (byte) Opcode.INSTANCEOF, (byte) (suspend >> 8), (byte) suspend,
            (byte) Opcode.IFEQ, 0, 4, // past the athrow, into the handler
            (byte) Opcode.ATHROW,
        };
        CodeAttribute code = info.getCodeAttribute();
        CodeIterator iterator = code.iterator();
        for (int pc : handlerPcs) {
            // not exclusive: the handler's entries keep pointing at pc, now the check, and a range ending at pc
            // (the try's own) still does not cover it
            iterator.insertAt(pc, check);
        }
        code.setMaxStack(code.computeMaxStack());
        info.rebuildStackMapIf6(classPool, method.getDeclaringClass().getClassFile());
    }

    /**
     * A handler javac writes for try-with-resources (PF-229): it catches Throwable, closes the resource and throws
     * what it caught again. ECJ compiled the same cleanup as a finally handler, which has no {@code $1} and so
     * never got the Result check; with javac the check made a render inside the try leave before close(), and the
     * action leaked its connection, stream or lock. A handler of this shape rethrows a Result itself, after its
     * cleanup, so it needs no check. javac keeps the caught throwable in a synthetic local, which the local
     * variable table does not name; a catch of the source always has its variable there. Read from the handler's
     * first instruction, an astore of the caught throwable, to the first athrow: there must be a call to a
     * no-argument close() on the way, and the athrow must throw that same local. A catch that ends otherwise, and
     * any catch of the source, however its variable is named, unnamed included, keeps the check: a catch that
     * closes, rethrows only some throwables and renders otherwise would swallow the render.
     */
    static boolean closesAndRethrows(Handler handler) {
        try {
            if (handler.isFinally() || !"java.lang.Throwable".equals(handler.getType().getName())) {
                return false;
            }
        } catch (NotFoundException e) {
            return false;
        }
        MethodInfo method = handler.where().getMethodInfo();
        ConstPool constPool = method.getConstPool();
        CodeIterator code = method.getCodeAttribute().iterator();
        code.move(handler.indexOfBytecode());
        try {
            int caught = storedLocal(code, code.next());
            if (caught < 0 || namesLocal(method, caught, code.lookAhead())) {
                return false;
            }
            boolean closes = false;
            int loaded = -1;
            while (code.hasNext()) {
                int index = code.next();
                int opcode = code.byteAt(index);
                if (opcode == Opcode.ATHROW) {
                    return closes && loaded == caught;
                }
                if (opcode >= Opcode.IRETURN && opcode <= Opcode.RETURN) {
                    return false;
                }
                if (opcode == Opcode.INVOKEINTERFACE || opcode == Opcode.INVOKEVIRTUAL) {
                    int ref = code.u16bitAt(index + 1);
                    String name = opcode == Opcode.INVOKEINTERFACE ? constPool.getInterfaceMethodrefName(ref)
                        : constPool.getMethodrefName(ref);
                    String type = opcode == Opcode.INVOKEINTERFACE ? constPool.getInterfaceMethodrefType(ref)
                        : constPool.getMethodrefType(ref);
                    closes |= "close".equals(name) && "()V".equals(type);
                }
                loaded = loadedLocal(code, index);
            }
        } catch (BadBytecode e) {
            return false;
        }
        return false;
    }

    /** Whether the method's local variable table names the local at {@code pc}. */
    private static boolean namesLocal(MethodInfo method, int local, int pc) {
        LocalVariableAttribute table = (LocalVariableAttribute) method.getCodeAttribute().getAttribute(LocalVariableAttribute.tag);
        for (int i = 0; table != null && i < table.tableLength(); i++) {
            if (table.index(i) == local && table.startPc(i) <= pc && pc < table.startPc(i) + table.codeLength(i)) {
                return true;
            }
        }
        return false;
    }

    /** The local an astore at {@code index} writes, or -1 when the instruction is none. */
    private static int storedLocal(CodeIterator code, int index) {
        int opcode = code.byteAt(index);
        if (opcode >= Opcode.ASTORE_0 && opcode <= Opcode.ASTORE_3) {
            return opcode - Opcode.ASTORE_0;
        }
        if (opcode == Opcode.ASTORE) {
            return code.byteAt(index + 1);
        }
        if (opcode == Opcode.WIDE && code.byteAt(index + 1) == Opcode.ASTORE) {
            return code.u16bitAt(index + 2);
        }
        return -1;
    }

    /** The local an aload at {@code index} reads, or -1 when the instruction is none. */
    private static int loadedLocal(CodeIterator code, int index) {
        int opcode = code.byteAt(index);
        if (opcode >= Opcode.ALOAD_0 && opcode <= Opcode.ALOAD_3) {
            return opcode - Opcode.ALOAD_0;
        }
        if (opcode == Opcode.ALOAD) {
            return code.byteAt(index + 1);
        }
        if (opcode == Opcode.WIDE && code.byteAt(index + 1) == Opcode.ALOAD) {
            return code.u16bitAt(index + 2);
        }
        return -1;
    }

    /**
     * Mark class that need controller enhancement
     */
    public static interface ControllerSupport {
    }

    /**
     * Check if a field must be translated to a 'thread safe field'
     */
    static boolean isThreadedFieldAccess(CtField field) {
        if (field.getDeclaringClass().getName().equals("play.mvc.Controller") || field.getDeclaringClass().getName().equals("play.mvc.WebSocketController")) {
            return field.getName().equals("params")
                    || field.getName().equals("request")
                    || field.getName().equals("response")
                    || field.getName().equals("session")
                    || field.getName().equals("params")
                    || field.getName().equals("renderArgs")
                    || field.getName().equals("routeArgs")
                    || field.getName().equals("validation")
                    || field.getName().equals("inbound")
                    || field.getName().equals("outbound")
                    || field.getName().equals("flash");
        }
        return false;
    }

    /**
     * Runtime part needed by the instrumentation
     */
    public static class ControllerInstrumentation {

        public static boolean isActionCallAllowed() {
            return allow.get();
        }

        public static void initActionCall() {
            allow.set(true);
        }

        public static void stopActionCall() {
            allow.set(false);
        }
        static final ThreadLocal<Boolean> allow = new PlayThreadLocal<>();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface ByPass {
    }

    static String generateValidReturnStatement(CtClass type) {
        if (type.equals(CtClass.voidType)) {
            return "return;";
        }
        if (type.equals(CtClass.booleanType)) {
            return "return false;";
        }
        if (type.equals(CtClass.charType)) {
            return "return '';";
        }
        if (type.equals(CtClass.byteType)) {
            return "return (byte)0;";
        }
        if (type.equals(CtClass.doubleType)) {
            return "return (double)0;";
        }
        if (type.equals(CtClass.floatType)) {
            return "return (float)0;";
        }
        if (type.equals(CtClass.intType)) {
            return "return (int)0;";
        }
        if (type.equals(CtClass.longType)) {
            return "return (long)0;";
        }
        if (type.equals(CtClass.shortType)) {
            return "return (short)0;";
        }
        return "return null;";
    }
}
