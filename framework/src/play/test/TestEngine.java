package play.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import play.utils.VirtualThreadFactory;

import org.junit.jupiter.api.Assertions;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.UnexpectedException;
import play.libs.F;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Router;
import play.mvc.Scope.RenderArgs;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Run application tests
 */
public class TestEngine {

    private static final class ClassNameComparator implements Comparator<Class> {
        @Override
        public int compare(Class aClass, Class bClass) {
            return aClass.getName().compareTo(bClass.getName());
        }
    }

    private static final ClassNameComparator classNameComparator = new ClassNameComparator();

    // Single-threaded so functional-test invocations serialize (tests share static
    // request/response/render state). Worker is a virtual thread; the "single-threaded"
    // semantic comes from newSingleThreadExecutor's queue, not from the carrier kind.
    public static final ExecutorService functionalTestsExecutor =
            Executors.newSingleThreadExecutor(new VirtualThreadFactory("functional-test"));

    private record AbandonedInvocation(String request, Future<?> future) {}

    private static volatile AbandonedInvocation abandonedInvocation;

    /**
     * Records an invocation that {@link FunctionalTest#makeRequest} gave up waiting for. It is
     * deliberately not cancelled: interrupting it mid-flight would leave the shared static
     * request/response/render state corrupt for every following test. It therefore keeps the
     * executor's only thread, and everything queued behind it would time out in turn.
     */
    static void abandonInvocation(String request, Future<?> future) {
        abandonedInvocation = new AbandonedInvocation(request, future);
    }

    /**
     * @return a description of the abandoned invocation still holding the executor thread, or
     *         {@code null} when the executor is usable. An abandoned invocation that has since
     *         finished has released the thread, so it stops blocking further requests.
     */
    static String blockedBy() {
        AbandonedInvocation abandoned = abandonedInvocation;
        if (abandoned == null) {
            return null;
        }
        if (abandoned.future().isDone()) {
            abandonedInvocation = null;
            return null;
        }
        return abandoned.request();
    }

    public static List<Class> allUnitTests() {
        List<Class> classes = new ArrayList<>();
        classes.addAll(Play.classloader.getAssignableClasses(Assertions.class));
        classes.addAll(Play.pluginCollection.getUnitTests());
        for (ListIterator<Class> it = classes.listIterator(); it.hasNext();) {
            Class c = it.next();
            if (Modifier.isAbstract(c.getModifiers())) {
                it.remove();
            } else {
                if (FunctionalTest.class.isAssignableFrom(c)) {
                    it.remove();
                }
            }
        }
        classes.sort(classNameComparator);
        return classes;
    }

    public static List<Class> allFunctionalTests() {
        List<Class> classes = new ArrayList<>();
        classes.addAll(Play.classloader.getAssignableClasses(FunctionalTest.class));
        classes.addAll(Play.pluginCollection.getFunctionalTests());

        classes.removeIf(aClass -> Modifier.isAbstract(aClass.getModifiers()));
        classes.sort(classNameComparator);
        return classes;
    }

    public static void initTest(Class<?> testClass) {
        CleanTest cleanTestAnnot = null;
        if (testClass != null) {
            cleanTestAnnot = testClass.getAnnotation(CleanTest.class);
        }
        if (cleanTestAnnot != null && cleanTestAnnot.removeCurrent()) {
            if (Request.current != null) {
                Request.current.remove();
            }
            if (Response.current != null) {
                Response.current.remove();
            }
            if (RenderArgs.current != null) {
                RenderArgs.current.remove();
            }
        }
        if (cleanTestAnnot == null || cleanTestAnnot.createDefault()) {
            if (Request.current() == null) {
                String host = Router.getBaseUrl();
                String domain = null;
                Integer port = 80;
                boolean isSecure = false;
                if (host == null || host.equals("application.baseUrl")) {
                    host = "localhost:" + port;
                    domain = "localhost";
                } else if (host.contains("http://")) {
                    host = host.replaceAll("http://", "");
                } else if (host.contains("https://")) {
                    host = host.replaceAll("https://", "");
                    port = 443;
                    isSecure = true;
                }
                int colonPos = host.indexOf(':');
                if (colonPos > -1) {
                    domain = host.substring(0, colonPos);
                    port = Integer.parseInt(host.substring(colonPos + 1));
                } else {
                    domain = host;
                }
                Request request = Request.createRequest(new Request.RequestData(null, "GET", "/", "", null,
                        null, null, host, false, port, domain, isSecure, null, null));
                request.body = new ByteArrayInputStream(new byte[0]);
                Request.current.set(request);
            }

            if (Response.current() == null) {
                Response response = new Response();
                response.out = new ByteArrayOutputStream();
                response.direct = null;
                Response.current.set(response);
            }

            if (RenderArgs.current() == null) {
                RenderArgs renderArgs = new RenderArgs();
                RenderArgs.current.set(renderArgs);
            }
        }
    }

    public static TestResults run(String name) {
        TestResults testResults = new TestResults();

        try {
            Class testClass = Play.classloader.loadClass(name);

            initTest(testClass);

            // PF-231: with play.test.transaction=none, no code of the class runs inside the
            // transaction of the job that the /@tests runner runs it in. PlayJUnitExtension sees
            // to that for a BaseTest on any runner; this also covers a class that is not one,
            // and the plugins' runners.
            TestResults pluginTestResults = asTestCode(() -> Play.pluginCollection.runTest(testClass));
            if (pluginTestResults != null) {
                return pluginTestResults;
            }

            // Prepare JUnit 5 launcher request
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(testClass))
                    .build();

            Launcher launcher = LauncherFactory.create();
            Listener listener = new Listener(testClass.getName(), testResults);

            launcher.registerTestExecutionListeners(listener);
            asTestCode(() -> {
                launcher.execute(request);
                return null;
            });
            // PF-241: a class none of whose tests ran is not a passed class
            if (testResults.results.isEmpty()) {
                TestResult none = new TestResult();
                none.name = testClass.getSimpleName();
                none.passed = false;
                none.error = "No test ran: JUnit found no test in " + testClass.getName();
                testResults.add(none);
            }

        } catch (ClassNotFoundException e) {
            Logger.error(e, "Test not found %s", name);
        }

        return testResults;
    }

    private static <T> T asTestCode(F.Function0<T> code) {
        try {
            return TestTransaction.run(code);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UnexpectedException(e);
        }
    }

    // ~~~~~~ Test Execution Listener (JUnit 5)
    static class Listener implements TestExecutionListener {

        final TestResults results;
        final String className;
        TestResult current;

        public Listener(String className, TestResults results) {
            this.results = results;
            this.className = className;
        }

        @Override
        public void executionStarted(TestIdentifier testIdentifier) {
            if (testIdentifier.isTest()) {
                current = new TestResult();
                current.name = testIdentifier.getDisplayName();
                current.time = System.currentTimeMillis();
            }
        }

        @Override
        public void executionSkipped(TestIdentifier testIdentifier, String reason) {
            // PF-241: a disabled class is reported as skipped, not as a class with nothing in it
            if (testIdentifier.isTest() || isClass(testIdentifier)) {
                current = new TestResult();
                current.name = testIdentifier.getDisplayName();
                current.time = 0;
                current.passed = true;
                current.skipped = true;
                current.error = "Skipped: " + reason;
                results.add(current);
            }
        }

        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
            if (testIdentifier.isTest()) {
                current.time = System.currentTimeMillis() - current.time;
                record(current, testExecutionResult);
                results.add(current);
            } else if (testExecutionResult.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                // PF-241: the class itself, a nested class or the engine failed or was aborted: a @BeforeAll or
                // @AfterAll that threw, a class initialiser, an extension's beforeAll, a nested class Jupiter could
                // not create. Before, only tests were recorded, so a class none of whose tests ran passed.
                TestResult container = new TestResult();
                container.name = testIdentifier.getDisplayName() + " (the class's setup or teardown)";
                record(container, testExecutionResult);
                results.add(container);
            }
        }

        private void record(TestResult result, TestExecutionResult testExecutionResult) {
            TestExecutionResult.Status status = testExecutionResult.getStatus();
            if (status == TestExecutionResult.Status.SUCCESSFUL) {
                result.passed = true;
            } else if (status == TestExecutionResult.Status.ABORTED) {
                result.passed = true;
                result.skipped = true;
                Throwable ex = testExecutionResult.getThrowable().orElse(null);
                result.error = ex != null ? "Skipped: " + ex.getMessage() : "Skipped";
            } else {
                result.passed = false;
                Throwable ex = testExecutionResult.getThrowable().orElse(null);
                if (ex == null) {
                    result.error = "A failure with no exception";
                    return;
                }
                if (ex instanceof AssertionError) {
                    result.error = "Failure, " + ex.getMessage();
                } else {
                    result.error = "A " + ex.getClass().getName() + " has been caught, " + ex.getMessage();
                }
                result.trace = getStackTrace(ex);
                ApplicationClass source = Play.classes == null ? null : Play.classes.getApplicationClass(className);
                if (source == null || source.javaFile == null) {
                    return;
                }
                // the class's own frame nearest the test, as before; a class initialiser that threw is only in the
                // cause of the ExceptionInInitializerError, so the causes are looked at when the exception has none
                for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
                    StackTraceElement frame = null;
                    for (StackTraceElement stackTraceElement : cause.getStackTrace()) {
                        String frameClass = stackTraceElement.getClassName();
                        if (frameClass.equals(className) || frameClass.startsWith(className + "$")) {
                            frame = stackTraceElement;
                        }
                    }
                    if (frame != null) {
                        result.sourceInfos = "In " + source.javaFile.relativePath() + ", line " + frame.getLineNumber();
                        String[] lines = source.javaSource.split("\n");
                        if (frame.getLineNumber() > 0 && frame.getLineNumber() - 1 < lines.length) {
                            result.sourceCode = lines[frame.getLineNumber() - 1];
                        }
                        result.sourceFile = source.javaFile.relativePath();
                        result.sourceLine = frame.getLineNumber();
                        return;
                    }
                }
            }
        }

        private static boolean isClass(TestIdentifier testIdentifier) {
            return testIdentifier.getSource().map(ClassSource.class::isInstance).orElse(false);
        }

        private String getStackTrace(Throwable throwable) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement element : throwable.getStackTrace()) {
                sb.append(element.toString()).append("\n");
            }
            return sb.toString();
        }
    }

    public static class TestResults {

        public List<TestResult> results = new ArrayList<>();
        public boolean passed = true;
        public int success = 0;
        public int errors = 0;
        public int failures = 0;
        public int skipped = 0;
        public long time = 0;

        public void add(TestResult result) {
            time = result.time + time;
            this.results.add(result);
            if (result.skipped) {
                skipped++;
            } else if (result.passed) {
                success++;
            } else {
                if (result.error != null && result.error.startsWith("Failure")) {
                    failures++;
                } else {
                    errors++;
                }
                passed = false;
            }
        }
    }

    public static class TestResult {

        public String name;
        public String error;
        public boolean passed = true;
        public boolean skipped = false;
        public long time;
        public String trace;
        public String sourceInfos;
        public String sourceCode;
        public String sourceFile;
        public int sourceLine;
    }
}