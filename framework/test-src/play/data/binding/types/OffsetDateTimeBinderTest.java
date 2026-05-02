package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

public class OffsetDateTimeBinderTest {

    private OffsetDateTimeBinder binder = new OffsetDateTimeBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void nullOffsetDateTime() {
        assertNull(binder.bind("event.start", null, null, OffsetDateTime.class, null));
    }

    @Test
    public void emptyOffsetDateTime() {
        assertNull(binder.bind("event.start", null, "", OffsetDateTime.class, null));
    }

    @Test
    public void whitespaceOffsetDateTime() {
        assertNull(binder.bind("event.start", null, "   ", OffsetDateTime.class, null));
    }

    @Test
    public void validOffsetDateTime() {
        OffsetDateTime expected = OffsetDateTime.parse("2026-05-02T10:15:30+01:00");
        OffsetDateTime actual = binder.bind("event.start", null, "2026-05-02T10:15:30+01:00", OffsetDateTime.class, null);
        assertEquals(expected, actual);
    }

    @Test
    public void invalidOffsetDateTime() {
        assertThrows(DateTimeParseException.class, () -> {
            binder.bind("event.start", null, "2026-13-02T10:15:30+01:00", OffsetDateTime.class, null);
        });
    }
}
