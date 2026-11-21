package play.test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import play.Invoker;
import play.Invoker.DirectInvocation;
import play.Play;

public class PlayJUnitExtension implements BeforeAllCallback, BeforeEachCallback, InvocationInterceptor,
        TestExecutionExceptionHandler {

    public static final String invocationType = "JUnitTest";

    public static boolean useCustomRunner = false;


    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        synchronized (Play.class) {
            if (!Play.started) {
                Play.init(new File("."), getPlayId());
                Play.javaPath.add(Play.getVirtualFile("test"));
                if (!Play.started) {
                    Play.start();
                }
                useCustomRunner = true;
            }
        }
        // Initialize Play for the test class if needed (equivalent to initTest in old code)
        Class<?> testClass = context.getRequiredTestClass();
        TestEngine.initTest(testClass);
    }


    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (useCustomRunner) {
            if (!Play.started) {
                Play.forceProd = true;
                Play.init(new File("."), getPlayId());
            }
            Invoker.invokeInThread(new DirectInvocation() {
                @Override
                public void execute() throws Exception {
                    // Nothing here; test method will run
                }
                @Override
                public Invoker.InvocationContext getInvocationContext() {
                    return new Invoker.InvocationContext(invocationType);
                }
            });
        }
    }

    // PF-231: every constructor and method of the test class runs as play.test.transaction
    // says, whichever runner runs it and whatever transaction that runner holds. The calls of one
    // outermost class share a TestTransaction.ClassScope, so under none a transaction that one of
    // them leaves open is still open in the next, as in a plain JUnit setup, and @Nested classes
    // see their enclosing class's. The scope lives in that class's store, which JUnit closes, and
    // with it the scope, when the class has finished (it closes an AutoCloseable it stores unless
    // junit.jupiter.extensions.store.close.autocloseable.enabled is false).

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
            .create(PlayJUnitExtension.class);

    @Override
    public <T> T interceptTestClassConstructor(Invocation<T> invocation,
            ReflectiveInvocationContext<Constructor<T>> invocationContext, ExtensionContext extensionContext)
            throws Throwable {
        return asTestCode(invocation, extensionContext);
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        asTestCode(invocation, extensionContext);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        asTestCode(invocation, extensionContext);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        asTestCode(invocation, extensionContext);
    }

    @Override
    public <T> T interceptTestFactoryMethod(Invocation<T> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        return asTestCode(invocation, extensionContext);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        asTestCode(invocation, extensionContext);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, DynamicTestInvocationContext invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        asTestCode(invocation, extensionContext);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        asTestCode(invocation, extensionContext);
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        asTestCode(invocation, extensionContext);
    }

    private static <T> T asTestCode(Invocation<T> invocation, ExtensionContext context) throws Throwable {
        return classScope(context).run(invocation::proceed);
    }

    private static TestTransaction.ClassScope classScope(ExtensionContext context) {
        ExtensionContext outermostClass = context;
        // the engine's context, the root, is the parent of an outermost class's
        while (outermostClass.getParent().flatMap(ExtensionContext::getParent).isPresent()) {
            outermostClass = outermostClass.getParent().get();
        }
        return outermostClass.getStore(NAMESPACE).computeIfAbsent(TestTransaction.ClassScope.class,
                key -> new TestTransaction.ClassScope(), TestTransaction.ClassScope.class);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        // Unwrap exceptions like in JUnit 4 runner
        Throwable root = ExceptionUtils.getRootCause(throwable);
        throw root != null ? root : throwable;
    }

    private static String getPlayId() {
        String playId = System.getProperty("play.id", "test");
        if(! (playId.startsWith("test-") && playId.length() >= 6)) {
            playId = "test";
        }
        return playId;
    }

}