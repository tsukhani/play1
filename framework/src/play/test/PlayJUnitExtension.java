package play.test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import play.Play;
import play.exceptions.UnexpectedException;
import play.libs.F;
import play.vfs.VirtualFile;

public class PlayJUnitExtension implements BeforeAllCallback, InvocationInterceptor, TestExecutionExceptionHandler {

    public static final String invocationType = "JUnitTest";

    /**
     * True when a JUnit Platform runner started Play in this JVM, {@link PlayTestSession} on
     * Gradle's test task or this extension itself, rather than the {@code /@tests} runner. Each
     * test class then runs in a job of its own, as {@code /@tests} runs it (PF-230).
     */
    public static boolean useCustomRunner = false;


    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        synchronized (Play.class) {
            if (!Play.started) {
                start();
            }
        }
        Class<?> testClass = context.getRequiredTestClass();
        try {
            classRun(context).onItsThread(() -> {
                TestEngine.initTest(testClass);
                return null;
            });
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UnexpectedException(e);
        }
    }

    /**
     * Start Play in test mode for the tests this JVM runs on the JUnit Platform, with the
     * application's test/ compiled beside app/, as the {@code /@tests} runner's server has it.
     */
    static void start() {
        init();
        startInitialized();
    }

    /**
     * The first half of {@link #start()}: the configuration read and the application's paths
     * set, test/ among them, so that a server can be bound before the application starts, as
     * {@code Server.main} binds it (PF-230).
     */
    static void init() {
        Play.init(new File(System.getProperty("application.path", ".")), getPlayId());
        // TestRunnerPlugin has added it already when play-testrunner.jar is on the classpath
        VirtualFile test = Play.getVirtualFile("test");
        if (!Play.javaPath.contains(test)) {
            Play.javaPath.add(test);
        }
    }

    /** The second half of {@link #start()}. */
    static void startInitialized() {
        if (!Play.started) {
            Play.start();
        }
        useCustomRunner = true;
    }

    // PF-231: every constructor and method of the test class runs as play.test.transaction
    // says, whichever runner runs it and whatever transaction that runner holds. The calls of one
    // outermost class share a TestTransaction.ClassScope, so under none a transaction that one of
    // them leaves open is still open in the next, as in a plain JUnit setup, and @Nested classes
    // see their enclosing class's. The scope lives in that class's store, beside the class's job
    // (PF-230), and JUnit closes both when the class has finished (it closes an AutoCloseable it
    // stores unless junit.jupiter.extensions.store.close.autocloseable.enabled is false).

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
        return classRun(context).asTestCode(invocation::proceed);
    }

    private static ClassRun classRun(ExtensionContext context) {
        ExtensionContext outermostClass = context;
        // the engine's context, the root, is the parent of an outermost class's
        while (outermostClass.getParent().flatMap(ExtensionContext::getParent).isPresent()) {
            outermostClass = outermostClass.getParent().get();
        }
        return outermostClass.getStore(NAMESPACE).computeIfAbsent(ClassRun.class, key -> new ClassRun(),
                ClassRun.class);
    }

    /**
     * The code of one outermost test class and its {@code @Nested} classes: their
     * {@link TestTransaction.ClassScope}, and, when a JUnit Platform runner started Play, the job
     * they run in (PF-230). Closed by JUnit with the class's store.
     */
    private static final class ClassRun implements AutoCloseable {

        private final TestTransaction.ClassScope scope = new TestTransaction.ClassScope();
        private final TestClassJob job = useCustomRunner ? new TestClassJob() : null;

        <T> T onItsThread(F.Function0<T> code) throws Throwable {
            return job == null ? code.apply() : job.run(code);
        }

        <T> T asTestCode(F.Function0<T> code) throws Throwable {
            return onItsThread(() -> scope.run(code));
        }

        @Override
        public void close() throws Exception {
            try {
                onItsThread(() -> {
                    scope.close();
                    return null;
                });
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new UnexpectedException(e);
            } finally {
                if (job != null) {
                    job.end();
                }
            }
        }
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