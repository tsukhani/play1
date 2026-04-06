package integration;

import java.io.File;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import play.Invoker;
import play.Invoker.DirectInvocation;
import play.Play;
import play.test.TestEngine;

/**
 * JUnit 5 extension that bootstraps Play with the integration test fixture app.
 * Similar to PlayJUnitExtension but points to test-src/integration/testapp/
 * instead of the current working directory.
 */
public class IntegrationTestExtension implements BeforeAllCallback, BeforeEachCallback, TestExecutionExceptionHandler {

    private static final String INVOCATION_TYPE = "IntegrationTest";

    private static File findTestApp() {
        File testApp = new File(System.getProperty("user.dir"), "test-src/integration/testapp");
        if (!testApp.isDirectory()) {
            throw new RuntimeException("Integration test fixture app not found at: " + testApp.getAbsolutePath());
        }
        return testApp;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        synchronized (Play.class) {
            if (!Play.started) {
                File testApp = findTestApp();
                Play.init(testApp, "test");
                if (!Play.started) {
                    Play.start();
                }
            }
        }
        Class<?> testClass = context.getRequiredTestClass();
        TestEngine.initTest(testClass);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (!Play.started) {
            Play.forceProd = true;
            Play.init(findTestApp(), "test");
        }
        Invoker.invokeInThread(new DirectInvocation() {
            @Override
            public void execute() throws Exception {
            }
            @Override
            public Invoker.InvocationContext getInvocationContext() {
                return new Invoker.InvocationContext(INVOCATION_TYPE);
            }
        });
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        Throwable root = ExceptionUtils.getRootCause(throwable);
        throw root != null ? root : throwable;
    }
}
