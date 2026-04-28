package play.data.binding.types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

public class CalendarBinderTest {

    private CalendarBinder binder = new CalendarBinder();

    @BeforeEach
    public void setup() {
        new PlayBuilder().build();
    }

    @Test
    public void parses_date_to_calendar() throws Exception {
        Play.configuration.setProperty("date.format", "dd.MM.yyyy");
        // Audit M21: CalendarBinder now pins parsing to UTC by default
        // (configurable via play.date.timezone). Match the binder's default TZ
        // so the comparison is between the same instant rather than
        // accidentally JVM-default vs UTC.
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date expected = fmt.parse("31.12.1986");
        Calendar actual = binder.bind("client.birthday", null, "31.12.1986", Calendar.class, null);
        assertEquals(expected, actual.getTime());
    }
    
    @Test
    public void parses_null_to_null() throws Exception {
        assertNull(binder.bind("client.birthday", null, null, Calendar.class, null));
    }
    
    @Test
    public void parses_empty_string_to_null() throws Exception {
        assertNull(binder.bind("client.birthday", null, "", Calendar.class, null));
    }

    @Test
    public void throws_ParseException_for_invalid_value() {
        assertThrows(ParseException.class, () -> {
            binder.bind("client.birthday", null, "12/31/1986", Calendar.class, null);
        });
    }
}