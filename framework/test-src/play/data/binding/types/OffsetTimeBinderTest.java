package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.time.OffsetTime;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

public class OffsetTimeBinderTest {

    private OffsetTimeBinder binder = new OffsetTimeBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void nullOffsetTime() {
        assertNull(binder.bind("event.start", null, null, OffsetTime.class, null));
    }

    @Test
    public void emptyOffsetTime() {
        assertNull(binder.bind("event.start", null, "", OffsetTime.class, null));
    }

    @Test
    public void whitespaceOffsetTime() {
        assertNull(binder.bind("event.start", null, "   ", OffsetTime.class, null));
    }

    @Test
    public void validOffsetTime() {
        OffsetTime expected = OffsetTime.parse("10:15:30+01:00");
        OffsetTime actual = binder.bind("event.start", null, "10:15:30+01:00", OffsetTime.class, null);
        assertEquals(expected, actual);
    }

    @Test
    public void invalidOffsetTime() {
        assertThrows(DateTimeParseException.class, () -> {
            binder.bind("event.start", null, "25:15:30+01:00", OffsetTime.class, null);
        });
    }
}
