package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.time.Year;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

public class YearBinderTest {

    private YearBinder binder = new YearBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void nullYear() {
        assertNull(binder.bind("event.year", null, null, Year.class, null));
    }

    @Test
    public void emptyYear() {
        assertNull(binder.bind("event.year", null, "", Year.class, null));
    }

    @Test
    public void whitespaceYear() {
        assertNull(binder.bind("event.year", null, "   ", Year.class, null));
    }

    @Test
    public void validYear() {
        Year expected = Year.of(2026);
        Year actual = binder.bind("event.year", null, "2026", Year.class, null);
        assertEquals(expected, actual);
    }

    @Test
    public void invalidYear() {
        assertThrows(DateTimeParseException.class, () -> {
            binder.bind("event.year", null, "twenty-twenty-six", Year.class, null);
        });
    }
}
