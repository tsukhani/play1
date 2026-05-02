package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.time.Duration;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

public class DurationBinderTest {

    private DurationBinder binder = new DurationBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void nullDuration() {
        assertNull(binder.bind("event.duration", null, null, Duration.class, null));
    }

    @Test
    public void emptyDuration() {
        assertNull(binder.bind("event.duration", null, "", Duration.class, null));
    }

    @Test
    public void whitespaceDuration() {
        assertNull(binder.bind("event.duration", null, "   ", Duration.class, null));
    }

    @Test
    public void validDuration() {
        Duration expected = Duration.ofMinutes(15);
        Duration actual = binder.bind("event.duration", null, "PT15M", Duration.class, null);
        assertEquals(expected, actual);
    }

    @Test
    public void invalidDuration() {
        assertThrows(DateTimeParseException.class, () -> {
            binder.bind("event.duration", null, "fifteen-minutes", Duration.class, null);
        });
    }
}
