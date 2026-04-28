package play.libs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class MailVirtualThreadTest {

    private Properties originalConfig;

    @BeforeEach
    void setUp() {
        originalConfig = new Properties();
        originalConfig.putAll(Play.configuration);
        Mail.resetExecutor();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = originalConfig;
        Mail.resetExecutor();
    }

    @Test
    void executorUsesCachedThreadPoolByDefault() {
        Play.configuration.remove("play.threads.virtual");
        Play.configuration.remove("play.threads.virtual.mail");

        java.util.concurrent.ExecutorService exec = Mail.getExecutor();
        assertThat(exec).isNotNull();
        // CachedThreadPool creates platform threads
        AtomicBoolean isVirtual = new AtomicBoolean(true);
        CountDownLatch latch = new CountDownLatch(1);
        exec.submit(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(isVirtual.get()).isFalse();
    }

    @Test
    void executorUsesVirtualThreadsWhenEnabled() {
        Play.configuration.setProperty("play.threads.virtual", "true");

        java.util.concurrent.ExecutorService exec = Mail.getExecutor();
        assertThat(exec).isNotNull();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        exec.submit(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void executorUsesVirtualThreadsWhenMailSubsystemEnabled() {
        Play.configuration.setProperty("play.threads.virtual", "false");
        Play.configuration.setProperty("play.threads.virtual.mail", "true");

        java.util.concurrent.ExecutorService exec = Mail.getExecutor();
        assertThat(exec).isNotNull();

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        exec.submit(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void mailGateDefaultsTo32Permits() {
        Play.configuration.remove("play.mail.maxConcurrent");

        Semaphore gate = Mail.getMailGate();
        assertThat(gate).isNotNull();
        assertThat(gate.availablePermits()).isEqualTo(32);
    }

    @Test
    void mailGateRespectsConfiguredCap() {
        Play.configuration.setProperty("play.mail.maxConcurrent", "8");

        Semaphore gate = Mail.getMailGate();
        assertThat(gate.availablePermits()).isEqualTo(8);
    }

    @Test
    void mailGateBlocksWhenAllPermitsHeld() throws Exception {
        // Verify the gate actually blocks: hold all permits, then a fresh acquire must wait.
        Play.configuration.setProperty("play.mail.maxConcurrent", "2");
        Semaphore gate = Mail.getMailGate();

        gate.acquire();
        gate.acquire();
        assertThat(gate.availablePermits()).isZero();

        // tryAcquire with a tight timeout must time out — proving back-pressure is real.
        boolean acquired = gate.tryAcquire(50, TimeUnit.MILLISECONDS);
        assertThat(acquired).isFalse();

        gate.release();
        gate.release();
    }
}
