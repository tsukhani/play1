package play.mvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;
import play.exceptions.JavaExecutionException;
import play.exceptions.UnexpectedException;
import play.mvc.results.Ok;
import play.mvc.results.Result;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-237: in PROD, {@link ActionInvoker#invoke(Method, Object, Object...)} calls a controller
 * method through a method handle once reflection has reached it, so the Result every action throws
 * is no longer wrapped in an InvocationTargetException that fills in its stack trace, which JFR's
 * exception events show. Every call must end as the reflective call ends in DEV: the same result,
 * the same exception or the same wrapping, and reflection's own refusals of the arguments.
 */
public class ActionInvokeHandleTest {

    public static class Target {
        public static final Result RESULT = new Ok();

        public static String hello(String name) {
            return "hello " + name;
        }

        public static String joined(String... parts) {
            return String.join("+", parts);
        }

        public static int twice(int n) {
            return 2 * n;
        }

        public static void result() {
            throw RESULT;
        }

        public static void failing() {
            throw new IllegalStateException("broken");
        }

        /** Called by one test only, so its first call in PROD is the reflective one. */
        public static void failingOnce() {
            throw new IllegalStateException("broken");
        }

        public static void unexpected() {
            throw new UnexpectedException("play's own");
        }

        public static void error() {
            throw new StackOverflowError("deep");
        }

        public String own() {
            return "own " + getClass().getSimpleName();
        }
    }

    public static class BadInit {
        static {
            if (Boolean.parseBoolean("true")) {
                throw new IllegalStateException("cannot initialise");
            }
        }

        public static void run() {
        }
    }

    private Play.Mode mode;

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
        mode = Play.mode;
    }

    @AfterEach
    public void tearDown() {
        Play.mode = mode;
    }

    @Test
    public void everyCallEndsAsTheReflectiveCallEnds() throws Exception {
        Object target = new Target();
        Object[][] calls = {
                { "hello", null, new Object[] { "you" } },
                { "hello", null, new Object[] { null } },
                { "hello", null, new Object[] { 7 } },
                { "hello", null, new Object[0] },
                { "hello", null, null },
                { "joined", null, new Object[] { new String[] { "a", "b" } } },
                { "joined", null, new Object[] { new String[0] } },
                { "joined", null, new Object[] { "a" } },
                { "joined", null, new Object[] { null } },
                { "twice", null, new Object[] { 21 } },
                { "twice", null, new Object[] { 21L } },
                { "twice", null, new Object[] { (short) 21 } },
                { "twice", null, new Object[] { null } },
                { "result", null, new Object[0] },
                { "result", null, null },
                { "failing", null, new Object[0] },
                { "unexpected", null, new Object[0] },
                { "error", null, new Object[0] },
                { "own", target, new Object[0] },
                { "own", null, new Object[0] },
                { "own", "not a target", new Object[0] },
        };
        for (Object[] call : calls) {
            Method method = method((String) call[0]);
            Object instance = call[1];
            Object[] args = (Object[]) call[2];
            Play.mode = Play.Mode.DEV;
            String reflective = outcome(() -> ActionInvoker.invoke(method, instance, args));
            Play.mode = Play.Mode.PROD;
            // The first call in PROD is reflective and makes the handle; the next ones use it.
            for (int i = 0; i < 3; i++) {
                assertThat(outcome(() -> ActionInvoker.invoke(method, instance, args)))
                        .as("%s(%s) on %s, call %d", call[0], args == null ? null : Arrays.asList(args), instance, i)
                        .isEqualTo(reflective);
            }
        }
    }

    @Test
    public void theActionsResultIsThrownAsItIs() throws Exception {
        Play.mode = Play.Mode.PROD;
        Method result = method("result");
        for (int i = 0; i < 3; i++) {
            try {
                ActionInvoker.invoke(result, null);
            } catch (Result thrown) {
                assertThat(thrown).isSameAs(Target.RESULT);
            }
        }
    }

    @Test
    public void onlyTheFirstCallInProdWrapsWhatTheMethodThrew(@TempDir Path dir) throws Exception {
        Method failing = method("failingOnce");
        Play.mode = Play.Mode.DEV;
        assertThat(wrappersMadeBy(failing, 3, dir.resolve("dev.jfr"))).isEqualTo(3);
        Play.mode = Play.Mode.PROD;
        assertThat(wrappersMadeBy(failing, 3, dir.resolve("prod.jfr"))).isEqualTo(1);
        assertThat(cause(failing)).isInstanceOf(IllegalStateException.class).hasMessage("broken");
    }

    /** How many InvocationTargetExceptions the calls made, as JFR's exception events count them. */
    private static long wrappersMadeBy(Method method, int calls, Path file) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("jdk.JavaExceptionThrow");
            recording.start();
            for (int i = 0; i < calls; i++) {
                cause(method);
            }
            recording.stop();
            recording.dump(file);
        }
        return RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().equals("jdk.JavaExceptionThrow"))
                .filter(e -> InvocationTargetException.class.getName().equals(e.getClass("thrownClass").getName()))
                .count();
    }

    @Test
    public void aClassThatFailsToInitialiseFailsAsReflectionFails() throws Exception {
        Play.mode = Play.Mode.PROD;
        Method run = BadInit.class.getMethod("run");
        assertThat(outcome(() -> ActionInvoker.invoke(run, null))).startsWith("java.lang.ExceptionInInitializerError");
        assertThat(outcome(() -> ActionInvoker.invoke(run, null))).startsWith("java.lang.NoClassDefFoundError");
        assertThat(outcome(() -> ActionInvoker.invoke(run, null))).startsWith("java.lang.NoClassDefFoundError");
    }

    private static Throwable cause(Method method) {
        try {
            ActionInvoker.invoke(method, null);
        } catch (JavaExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("nothing thrown");
    }

    private static Method method(String name) {
        for (Method m : Target.class.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError(name);
    }

    /** How a call ended: its result, or the classes and messages of what it threw and its cause. */
    private static String outcome(Callable<Object> call) {
        try {
            return "returned " + call.call();
        } catch (Throwable t) {
            String cause = t.getCause() == null ? "" : " caused by " + t.getCause().getClass().getName() + ": "
                    + t.getCause().getMessage();
            return t.getClass().getName() + ": " + (t instanceof Result ? "" : t.getMessage()) + cause;
        }
    }
}
