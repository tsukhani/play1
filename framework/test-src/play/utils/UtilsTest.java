package play.utils;

import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UtilsTest {

    private static Date dateTime(int y, int mo, int d, int h, int mi, int s) {
        return Date.from(LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.systemDefault()).toInstant());
    }

    private static Date dateOnly(int y, int mo, int d) {
        return dateTime(y, mo, d, 0, 0, 0);
    }

    @Test
    public void defaultFormatter_parses_iso8601_with_literal_Z() throws ParseException {
        assertThat(Utils.AlternativeDateFormat.getDefaultFormatter().parse("2023-04-15T10:30:45Z"))
            .isEqualTo(dateTime(2023, 4, 15, 10, 30, 45));
    }

    @Test
    public void defaultFormatter_parses_iso8601_no_zone() throws ParseException {
        assertThat(Utils.AlternativeDateFormat.getDefaultFormatter().parse("2023-04-15T10:30:45"))
            .isEqualTo(dateTime(2023, 4, 15, 10, 30, 45));
    }

    @Test
    public void defaultFormatter_parses_space_separated_datetime() throws ParseException {
        assertThat(Utils.AlternativeDateFormat.getDefaultFormatter().parse("2023-04-15 10:30:45"))
            .isEqualTo(dateTime(2023, 4, 15, 10, 30, 45));
    }

    @Test
    public void defaultFormatter_parses_date_only_returns_midnight_local() throws ParseException {
        assertThat(Utils.AlternativeDateFormat.getDefaultFormatter().parse("2023-04-15"))
            .isEqualTo(dateOnly(2023, 4, 15));
    }

    @Test
    public void defaultFormatter_parses_ddmmyyyy_compact() throws ParseException {
        // 8-digit compact dates resolve via ddMMyyyy (yyyyMMdd is intentionally not in the
        // default pattern list — keep this asymmetry so the migration doesn't change semantics).
        assertThat(Utils.AlternativeDateFormat.getDefaultFormatter().parse("15042023"))
            .isEqualTo(dateOnly(2023, 4, 15));
    }

    @Test
    public void defaultFormatter_parses_yyyymmdd_hhmmss_compact() throws ParseException {
        assertThat(Utils.AlternativeDateFormat.getDefaultFormatter().parse("20230415 103045"))
            .isEqualTo(dateTime(2023, 4, 15, 10, 30, 45));
    }

    @Test
    public void defaultFormatter_parses_dd_slash_mm_slash_yyyy() throws ParseException {
        assertThat(Utils.AlternativeDateFormat.getDefaultFormatter().parse("15/04/2023"))
            .isEqualTo(dateOnly(2023, 4, 15));
    }

    @Test
    public void defaultFormatter_throws_on_unrecognized_input() {
        assertThatThrownBy(() -> Utils.AlternativeDateFormat.getDefaultFormatter().parse("not-a-date"))
            .isInstanceOf(ParseException.class);
    }

    @Test
    public void defaultFormatter_throws_on_length_mismatch() {
        // Length filter: a string close to a known pattern but with extra chars must fail, not silently
        // truncate-parse. Keeps the original SimpleDateFormat behavior of rejecting trailing garbage.
        assertThatThrownBy(() -> Utils.AlternativeDateFormat.getDefaultFormatter().parse("2023-04-15T10:30:45Z extra"))
            .isInstanceOf(ParseException.class);
    }

    @Test
    public void setFormats_appends_to_existing_patterns() throws ParseException {
        Utils.AlternativeDateFormat f = new Utils.AlternativeDateFormat(Locale.US, "yyyy-MM-dd");
        f.setFormats("dd.MM.yyyy");
        assertThat(f.parse("2023-04-15")).isEqualTo(dateOnly(2023, 4, 15));
        assertThat(f.parse("15.04.2023")).isEqualTo(dateOnly(2023, 4, 15));
    }


    @Test
    public void mergeSingleValueInMap() {
        Map<String, String[]> map = new HashMap<>();

        Utils.Maps.mergeValueInMap(map, "key1", "value");
        assertThat(map)
            .containsOnly(Map.entry("key1", new String[] { "value" }));

        Utils.Maps.mergeValueInMap(map, "key1", "value");
        assertThat(map)
            .containsOnly(Map.entry("key1", new String[] { "value", "value" }));

        Utils.Maps.mergeValueInMap(map, "key2", "value");
        assertThat(map).containsOnly(
            Map.entry("key1", new String[] { "value", "value" }),
            Map.entry("key2", new String[] { "value" })
        );
    }

    @Test
    public void mergeArrayValuesInMap() {
        Map<String, String[]> map = new HashMap<>();

        Utils.Maps.mergeValueInMap(map, "key1", new String[] { "value" });
        assertThat(map)
            .containsOnly(Map.entry("key1", new String[] { "value" }));

        Utils.Maps.mergeValueInMap(map, "key1", new String[] { "value", "value" });
        assertThat(map)
            .containsOnly(Map.entry("key1", new String[] { "value", "value", "value" }));

        Utils.Maps.mergeValueInMap(map, "key2", new String[]{ "value", "value", "value" });
        assertThat(map).containsOnly(
            Map.entry("key1", new String[] { "value", "value", "value" }),
            Map.entry("key2", new String[] { "value", "value", "value" })
        );
    }

}