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

/**
 * Integration test that verifies virtual thread support works end-to-end
 * when {@code play.threads.virtual = true} is set.
 */
public class VirtualThreadIntegrationTest {

    private Properties originalConfig;

    @BeforeEach
    void setUp() {
        originalConfig = new Properties();
        originalConfig.putAll(Play.configuration);
    }

    @AfterEach
    void tearDown() {
        Play.configuration = originalConfig;
        Invoker.init();
    }

    @Test
    void requestInvocationExecutesOnVirtualThread() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Invoker.init();

        assertThat(Invoker.usingVirtualThreads).isTrue();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Invoker.Invocation invocation = new Invoker.Invocation() {
            @Override
            public void execute() {
                isVirtual.set(Thread.currentThread().isVirtual());
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            }

            @Override
            public boolean init() {
                InvocationContext.current.set(getInvocationContext());
                return true;
            }

            @Override
            public InvocationContext getInvocationContext() {
                return new InvocationContext("IntegrationTest");
            }

            @Override
            public void before() {}
            @Override
            public void after() {}
            @Override
            public void onSuccess() {}
            @Override
            public void _finally() {}
        };

        Invoker.invoke(invocation);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(isVirtual.get()).isTrue();
        assertThat(threadName.get()).startsWith("play-vthread-");
    }

    @Test
    void delayedInvocationExecutesOnVirtualThread() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Invoker.init();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Invoker.Invocation invocation = new Invoker.Invocation() {
            @Override
            public void execute() {
                isVirtual.set(Thread.currentThread().isVirtual());
                latch.countDown();
            }

            @Override
            public boolean init() {
                InvocationContext.current.set(getInvocationContext());
                return true;
            }

            @Override
            public InvocationContext getInvocationContext() {
                return new InvocationContext("IntegrationTest");
            }

            @Override
            public void before() {}
            @Override
            public void after() {}
            @Override
            public void onSuccess() {}
            @Override
            public void _finally() {}
        };

        long start = System.nanoTime();
        Invoker.invoke(invocation, 100);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsed).isGreaterThanOrEqualTo(50);
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void platformThreadModeStillWorks() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "false");
        Invoker.init();

        assertThat(Invoker.usingVirtualThreads).isFalse();

        AtomicBoolean isVirtual = new AtomicBoolean(true);
        CountDownLatch latch = new CountDownLatch(1);

        Invoker.Invocation invocation = new Invoker.Invocation() {
            @Override
            public void execute() {
                isVirtual.set(Thread.currentThread().isVirtual());
                latch.countDown();
            }

            @Override
            public boolean init() {
                InvocationContext.current.set(getInvocationContext());
                return true;
            }

            @Override
            public InvocationContext getInvocationContext() {
                return new InvocationContext("IntegrationTest");
            }

            @Override
            public void before() {}
            @Override
            public void after() {}
            @Override
            public void onSuccess() {}
            @Override
            public void _finally() {}
        };

        Invoker.invoke(invocation);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(isVirtual.get()).isFalse();
    }
}
