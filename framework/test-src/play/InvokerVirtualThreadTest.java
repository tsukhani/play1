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

        assertThat(Invoker.usingVirtualThreads).isFalse();
        assertThat(Invoker.executor).isNotNull();
        assertThat(Invoker.virtualExecutor).isNull();
    }

    @Test
    void initCreatesVirtualExecutorWhenGlobalEnabled() {
        Play.configuration.setProperty("play.threads.virtual", "true");

        Invoker.init();

        assertThat(Invoker.usingVirtualThreads).isTrue();
        assertThat(Invoker.virtualExecutor).isNotNull();
        assertThat(Invoker.executor).isNull();
    }

    @Test
    void initCreatesVirtualExecutorWhenInvokerEnabled() {
        Play.configuration.setProperty("play.threads.virtual", "false");
        Play.configuration.setProperty("play.threads.virtual.invoker", "true");

        Invoker.init();

        assertThat(Invoker.usingVirtualThreads).isTrue();
        assertThat(Invoker.virtualExecutor).isNotNull();
    }

    @Test
    void initCreatesPlatformExecutorWhenInvokerDisabledOverridesGlobal() {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Play.configuration.setProperty("play.threads.virtual.invoker", "false");

        Invoker.init();

        assertThat(Invoker.usingVirtualThreads).isFalse();
        assertThat(Invoker.executor).isNotNull();
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
}
