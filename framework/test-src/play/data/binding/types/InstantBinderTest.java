package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

public class InstantBinderTest {

    private InstantBinder binder = new InstantBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void nullInstant() {
        assertNull(binder.bind("event.start", null, null, Instant.class, null));
    }

    @Test
    public void emptyInstant() {
        assertNull(binder.bind("event.start", null, "", Instant.class, null));
    }

    @Test
    public void whitespaceInstant() {
        assertNull(binder.bind("event.start", null, "   ", Instant.class, null));
    }

    @Test
    public void validInstant() {
        Instant expected = Instant.parse("2026-05-02T10:15:30Z");
        Instant actual = binder.bind("event.start", null, "2026-05-02T10:15:30Z", Instant.class, null);
        assertEquals(expected, actual);
    }

    @Test
    public void invalidInstant() {
        assertThrows(DateTimeParseException.class, () -> {
            binder.bind("event.start", null, "not-an-instant", Instant.class, null);
        });
    }
}
