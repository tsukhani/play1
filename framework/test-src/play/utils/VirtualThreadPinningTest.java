package play.utils;

import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import play.Invoker;
import play.Invoker.InvocationContext;
import play.Play;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5: ensure normal Invoker dispatch on a VT carrier does not pin.
 *
 * <p>JEP 491 (Java 24+) removed pinning under {@code synchronized}, so on Java 25 a
 * baseline Play invocation should produce zero {@code jdk.VirtualThreadPinned} events.
 * If a future change re-introduces an unintentional {@code Object.wait} or native
 * {@code synchronized} on a hot path, this test catches it.</p>
 *
 * <p>Tagged {@code vt-pinning} so CI can opt in/out depending on the target JDK.</p>
 */
@Tag("vt-pinning")
public class VirtualThreadPinningTest {

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
    void invocationDoesNotPinCarrier() throws Exception {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Invoker.init();

        AtomicInteger pinEvents = new AtomicInteger();
        try (RecordingStream rs = new RecordingStream()) {
            // Threshold 0 catches every pin; the test's own Thread.sleep is non-pinning
            // on a VT, so it should not contribute events here.
            rs.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(0));
            rs.onEvent("jdk.VirtualThreadPinned", e -> pinEvents.incrementAndGet());
            rs.startAsync();

            int n = 5;
            CountDownLatch latch = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                Invoker.invoke(new Invoker.Invocation() {
                    @Override
                    public void execute() throws Exception {
                        // Non-pinning sleep: lets the VT actually park and resume so any
                        // unintended pin in the dispatch path would surface as an event.
                        Thread.sleep(10);
                        latch.countDown();
                    }

                    @Override
                    public boolean init() {
                        InvocationContext.current.set(getInvocationContext());
                        return true;
                    }

                    @Override
                    public InvocationContext getInvocationContext() {
                        return new InvocationContext("PinningTest");
                    }

                    @Override
                    public void before() {}

                    @Override
                    public void after() {}

                    @Override
                    public void onSuccess() {}
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            // Give JFR a moment to drain queued events to the consumer thread.
            Thread.sleep(200);
        }

        assertThat(pinEvents.get())
                .as("Invoker dispatch produced %d VirtualThreadPinned event(s); a hot path is pinning the carrier",
                        pinEvents.get())
                .isZero();
    }
}
