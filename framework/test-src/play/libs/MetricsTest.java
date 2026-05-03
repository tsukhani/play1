package play.libs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Metrics} (PF-13). Each test installs a fresh
 * {@link SimpleMeterRegistry} so meters don't leak across cases.
 */
public class MetricsTest {

    private MeterRegistry previous;

    @BeforeEach
    public void setUp() {
        previous = Metrics.registry();
        Metrics.install(new SimpleMeterRegistry());
    }

    @AfterEach
    public void tearDown() {
        Metrics.install(previous);
    }

    @Test
    public void counterIncrementsRegistered() {
        Metrics.counter("foo").increment();
        Metrics.counter("foo").increment();

        Counter found = Metrics.registry().find("foo").counter();
        assertThat(found).isNotNull();
        assertThat(found.count()).isEqualTo(2.0);
    }

    @Test
    public void timerRecordsDuration() {
        Metrics.timer("bar").record(Duration.ofMillis(5));
        Metrics.timer("bar").record(Duration.ofMillis(10));

        Timer found = Metrics.registry().find("bar").timer();
        assertThat(found).isNotNull();
        assertThat(found.count()).isEqualTo(2);
        assertThat(found.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
    }

    @Test
    public void gaugeReportsValue() {
        AtomicInteger value = new AtomicInteger(42);
        Metrics.gauge("baz", value, AtomicInteger::doubleValue);

        assertThat(Metrics.registry().find("baz").gauge()).isNotNull();
        assertThat(Metrics.registry().find("baz").gauge().value()).isEqualTo(42.0);
    }

    @Test
    public void counterTagsRecordedSeparately() {
        Metrics.counter("requests", "status", "200").increment();
        Metrics.counter("requests", "status", "500").increment(3);

        assertThat(Metrics.registry().find("requests").tag("status", "200").counter().count()).isEqualTo(1.0);
        assertThat(Metrics.registry().find("requests").tag("status", "500").counter().count()).isEqualTo(3.0);
    }
}
