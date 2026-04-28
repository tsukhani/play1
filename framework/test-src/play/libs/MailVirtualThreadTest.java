package play.libs;

import org.apache.commons.mail2.jakarta.Email;
import org.apache.commons.mail2.jakarta.SimpleEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4-mail + H4 coverage for {@link Mail}.
 *
 * <p>Mail keeps two pieces of state behind a synchronized lazy-init: the {@link java.util.concurrent.ExecutorService}
 * and the {@link Semaphore} gate. Each test calls {@link Mail#resetExecutor()} in
 * {@link #setUp()} so configuration changes take effect.</p>
 */
public class MailVirtualThreadTest {

    private Properties originalConfig;

    @BeforeEach
    void setUp() {
        originalConfig = new Properties();
        originalConfig.putAll(Play.configuration);
        Mail.resetExecutor();
        Mail.resetMailSystem();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = originalConfig;
        Mail.resetExecutor();
        Mail.resetMailSystem();
    }

    /**
     * L3 (naming) + L4-mail: when {@code play.threads.virtual.mail=true}, a task
     * dispatched through {@link Mail#getExecutor()} must run on a VT named
     * {@code mail-vthread-N}. This is the executor that {@link Mail#sendMessage}
     * routes through, so the assertion is equivalent to "mail dispatch lands on a VT".
     *
     * <p>We don't drive {@link Mail#send(Email)} end-to-end because that path requires
     * a real Email#send() / SMTP server; substituting {@link MailSystem} bypasses
     * the executor entirely (each MailSystem decides its own dispatch). Asserting on
     * {@code getExecutor()} directly is equivalent for VT plumbing and keeps the test
     * hermetic.</p>
     */
    @Test
    void mailExecutorUsesVirtualThreadsWhenEnabled() throws Exception {

        AtomicBoolean isVirtual = new AtomicBoolean(false);
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Mail.getExecutor().submit(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(isVirtual.get()).isTrue();
        assertThat(threadName.get()).startsWith("mail-vthread-");
    }

    /**
     * Verify the {@link Mail#getMailGate} cap is enforced end-to-end: with
     * {@code play.mail.maxConcurrent=1} and two concurrent in-flight sends submitted
     * through {@link Mail#sendMessage} (which is the production dispatch path that
     * gates and pumps through {@code getExecutor()}), the second send must not enter
     * its body until the first releases its permit.
     *
     * <p>We use a {@link SimpleEmail} subclass whose {@code send()} is replaced with a
     * controllable latch — bypassing SMTP without bypassing the executor or the gate.</p>
     */
    @Test
    void mailGateLimitsConcurrency() throws Exception {
        Play.configuration.setProperty("play.mail.maxConcurrent", "1");

        CountDownLatch firstEnteredSend = new CountDownLatch(1);
        CountDownLatch firstMayFinish = new CountDownLatch(1);
        CountDownLatch secondEnteredSend = new CountDownLatch(1);

        // First "email" — blocks inside send() until firstMayFinish is released.
        Email first = new SimpleEmail() {
            @Override
            public String send() {
                firstEnteredSend.countDown();
                try {
                    firstMayFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return "first";
            }
        };
        Email second = new SimpleEmail() {
            @Override
            public String send() {
                secondEnteredSend.countDown();
                return "second";
            }
        };

        Future<Boolean> f1 = Mail.sendMessage(first);
        // Wait for first to actually be holding the gate before submitting second.
        assertThat(firstEnteredSend.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> f2 = Mail.sendMessage(second);

        // While the first send still holds the only permit, the second must NOT
        // have entered Email.send() yet.
        assertThat(secondEnteredSend.await(200, TimeUnit.MILLISECONDS)).isFalse();
        // f2 cannot have completed (it's blocked acquiring the gate).
        try {
            f2.get(100, TimeUnit.MILLISECONDS);
            org.junit.jupiter.api.Assertions.fail("second send completed while gate was held");
        } catch (TimeoutException expected) {
            // good
        } catch (ExecutionException ee) {
            org.junit.jupiter.api.Assertions.fail("second send failed unexpectedly: " + ee.getCause());
        }

        // Release the first; the second should now proceed.
        firstMayFinish.countDown();
        assertThat(f1.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondEnteredSend.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(f2.get(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * H4: {@link Mail#getMailGate} must default to 32 permits when {@code Play.configuration}
     * is null (test/early-init paths) instead of NPEing.
     */
    @Test
    void getMailGateGuardsAgainstNullPlayConfiguration() {
        Properties saved = Play.configuration;
        try {
            Play.configuration = null;
            Mail.resetExecutor(); // invalidate cached gate so we re-read config

            Semaphore gate = Mail.getMailGate();
            assertThat(gate).isNotNull();
            assertThat(gate.availablePermits()).isEqualTo(32);
        } finally {
            Play.configuration = saved;
            Mail.resetExecutor();
        }
    }

    /**
     * H4: {@link Mail#getMailGate} must default to 32 permits when
     * {@code play.mail.maxConcurrent} is unparseable instead of bubbling a
     * NumberFormatException out of the next mail send.
     */
    @Test
    void getMailGateGuardsAgainstUnparseableConfig() {
        Play.configuration.setProperty("play.mail.maxConcurrent", "not-a-number");
        Mail.resetExecutor(); // invalidate cached gate so we re-read config

        Semaphore gate = Mail.getMailGate();
        assertThat(gate).isNotNull();
        assertThat(gate.availablePermits()).isEqualTo(32);
    }
}
