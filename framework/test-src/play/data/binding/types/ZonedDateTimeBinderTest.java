package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

public class ZonedDateTimeBinderTest {

    private ZonedDateTimeBinder binder = new ZonedDateTimeBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void nullZonedDateTime() {
        assertNull(binder.bind("event.start", null, null, ZonedDateTime.class, null));
    }

    @Test
    public void emptyZonedDateTime() {
        assertNull(binder.bind("event.start", null, "", ZonedDateTime.class, null));
    }

    @Test
    public void whitespaceZonedDateTime() {
        assertNull(binder.bind("event.start", null, "   ", ZonedDateTime.class, null));
    }

    @Test
    public void validZonedDateTime() {
        ZonedDateTime expected = ZonedDateTime.parse("2026-05-02T10:15:30+01:00[Europe/Paris]");
        ZonedDateTime actual = binder.bind("event.start", null, "2026-05-02T10:15:30+01:00[Europe/Paris]", ZonedDateTime.class, null);
        assertEquals(expected, actual);
    }

    @Test
    public void invalidZonedDateTime() {
        assertThrows(DateTimeParseException.class, () -> {
            binder.bind("event.start", null, "2026-13-02T10:15:30+01:00[Europe/Paris]", ZonedDateTime.class, null);
        });
    }
}
