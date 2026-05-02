package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

public class YearMonthBinderTest {

    private YearMonthBinder binder = new YearMonthBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void nullYearMonth() {
        assertNull(binder.bind("event.month", null, null, YearMonth.class, null));
    }

    @Test
    public void emptyYearMonth() {
        assertNull(binder.bind("event.month", null, "", YearMonth.class, null));
    }

    @Test
    public void whitespaceYearMonth() {
        assertNull(binder.bind("event.month", null, "   ", YearMonth.class, null));
    }

    @Test
    public void validYearMonth() {
        YearMonth expected = YearMonth.of(2026, 5);
        YearMonth actual = binder.bind("event.month", null, "2026-05", YearMonth.class, null);
        assertEquals(expected, actual);
    }

    @Test
    public void invalidYearMonth() {
        assertThrows(DateTimeParseException.class, () -> {
            binder.bind("event.month", null, "2026-13", YearMonth.class, null);
        });
    }
}
