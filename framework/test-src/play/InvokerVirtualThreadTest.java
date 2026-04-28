package play;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Invoker.InvocationContext;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class InvokerVirtualThreadTest {

    private Properties originalConfig;

    @BeforeEach
    void setUp() {
        originalConfig = new Properties();
        originalConfig.putAll(Play.configuration);
    }

    @AfterEach
    void tearDown() {
        Play.configuration = originalConfig;
        // Re-init with original config to reset state
        Invoker.init();
    }

    @Test
    void initCreatesPlatformExecutorByDefault() {
        Play.configuration.remove("play.threads.virtual");
        Play.configuration.remove("play.threads.virtual.invoker");

        Invoker.init();

        assertThat(Invoker.scheduler.isUsingVirtualThreads()).isFalse();
        assertThat(Invoker.scheduler.platformExecutor()).isNotNull();
        assertThat(Invoker.scheduler.virtualExecutor()).isNull();
    }

    @Test
    void initCreatesVirtualExecutorWhenGlobalEnabled() {
        Play.configuration.setProperty("play.threads.virtual", "true");

        Invoker.init();

        assertThat(Invoker.scheduler.isUsingVirtualThreads()).isTrue();
        assertThat(Invoker.scheduler.virtualExecutor()).isNotNull();
        assertThat(Invoker.scheduler.platformExecutor()).isNull();
    }

    @Test
    void initCreatesVirtualExecutorWhenInvokerEnabled() {
        Play.configuration.setProperty("play.threads.virtual", "false");
        Play.configuration.setProperty("play.threads.virtual.invoker", "true");

        Invoker.init();

        assertThat(Invoker.scheduler.isUsingVirtualThreads()).isTrue();
        assertThat(Invoker.scheduler.virtualExecutor()).isNotNull();
    }

    @Test
    void initCreatesPlatformExecutorWhenInvokerDisabledOverridesGlobal() {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Play.configuration.setProperty("play.threads.virtual.invoker", "false");

        Invoker.init();

        assertThat(Invoker.scheduler.isUsingVirtualThreads()).isFalse();
        assertThat(Invoker.scheduler.platformExecutor()).isNotNull();
    }

    @Test
    void invocationRunsOnVirtualThread() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Invoker.init();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        AtomicReference<InvocationContext> context = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Invoker.Invocation invocation = new Invoker.Invocation() {
            @Override
            public void execute() {
                isVirtual.set(Thread.currentThread().isVirtual());
                context.set(InvocationContext.current());
                latch.countDown();
            }

            @Override
            public boolean init() {
                InvocationContext.current.set(getInvocationContext());
                return true;
            }

            @Override
            public InvocationContext getInvocationContext() {
                return new InvocationContext("VirtualThreadTest");
            }

            @Override
            public void before() {}

            @Override
            public void after() {}

            @Override
            public void onSuccess() {}

            @Override
            public void _finally() {
                // Don't remove context before we read it in test
            }
        };

        Invoker.invoke(invocation);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(isVirtual.get()).isTrue();
        assertThat(context.get()).isNotNull();
        assertThat(context.get().getInvocationType()).isEqualTo("VirtualThreadTest");
    }

    /**
     * L7: verify that the production {@code _finally()} hook (which calls
     * {@code InvocationContext.current.remove()}) actually runs cleanly on a virtual
     * thread carrier. Unlike {@link #invocationRunsOnVirtualThread} this test does NOT
     * override {@code _finally()} to a no-op — it lets the production default run and
     * snapshots the InvocationContext from inside {@code execute()}, then waits for the
     * VT to exit before asserting.
     */
    @Test
    void finallyClearsInvocationContextOnVirtualThread() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Invoker.init();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        AtomicReference<InvocationContext> contextDuringExecute = new AtomicReference<>();
        CountDownLatch executeLatch = new CountDownLatch(1);
        CountDownLatch finallyLatch = new CountDownLatch(1);

        Invoker.Invocation invocation = new Invoker.Invocation() {
            @Override
            public void execute() {
                isVirtual.set(Thread.currentThread().isVirtual());
                contextDuringExecute.set(InvocationContext.current());
                executeLatch.countDown();
            }

            @Override
            public boolean init() {
                InvocationContext.current.set(getInvocationContext());
                return true;
            }

            @Override
            public InvocationContext getInvocationContext() {
                return new InvocationContext("FinallyLifecycleTest");
            }

            @Override
            public void before() {}

            @Override
            public void after() {}

            @Override
            public void onSuccess() {}

            @Override
            public void _finally() {
                // Call super so the production default (InvocationContext.current.remove(),
                // pluginCollection.invocationFinally()) actually runs. Then signal so the
                // test thread knows the lifecycle hook completed without throwing.
                try {
                    super._finally();
                } finally {
                    finallyLatch.countDown();
                }
            }
        };

        Invoker.invoke(invocation);
        assertThat(executeLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(finallyLatch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(isVirtual.get()).isTrue();
        assertThat(contextDuringExecute.get()).isNotNull();
        assertThat(contextDuringExecute.get().getInvocationType()).isEqualTo("FinallyLifecycleTest");
        // The test thread never had InvocationContext set, so it should still be null.
        assertThat(InvocationContext.current()).isNull();
    }

    /**
     * Verify that {@link Invoker#inflightInvocations} returns to its initial value
     * after a normal invocation completes, and that {@link Invoker#totalInvocations}
     * was incremented by exactly one.
     */
    @Test
    void inflightCounterIsDecrementedAfterInvocation() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Invoker.init();

        long initialInflight = Invoker.inflightInvocations.get();
        long initialTotal = Invoker.totalInvocations.get();

        CountDownLatch latch = new CountDownLatch(1);

        Invoker.Invocation invocation = new Invoker.Invocation() {
            @Override
            public void execute() {
                latch.countDown();
            }

            @Override
            public boolean init() {
                InvocationContext.current.set(getInvocationContext());
                return true;
            }

            @Override
            public InvocationContext getInvocationContext() {
                return new InvocationContext("CounterTest");
            }

            @Override
            public void before() {}

            @Override
            public void after() {}

            @Override
            public void onSuccess() {}
        };

        java.util.concurrent.Future<?> future = Invoker.invoke(invocation);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // future.get() blocks until the wrapper's finally has run (and thus the
        // inflight counter has been decremented). Without this barrier the test
        // could observe the counter mid-flight.
        future.get(5, TimeUnit.SECONDS);

        assertThat(Invoker.inflightInvocations.get()).isEqualTo(initialInflight);
        assertThat(Invoker.totalInvocations.get()).isEqualTo(initialTotal + 1);
    }
}
