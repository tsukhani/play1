package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Date;
import org.junit.jupiter.api.Test;

/**
 * PF-138: InFutureCheck / InPastCheck — date comparison.
 *
 * DETERMINISM: instead of relying on {@code new Date()} ("now"), we pin the reference to a
 * FIXED date by giving the annotation an explicit value() ("2020-06-15", parsed by
 * AlternativeDateFormat's "yyyy-MM-dd"). All comparisons are then against that frozen
 * instant, so the tests are not time-of-day or timezone-of-run flaky. (We do additionally
 * keep one "now"-relative sanity case per check using dates decades away, which is robust
 * regardless of when/where the suite runs.)
 *
 * Real behaviour read from source:
 *  - null ALWAYS passes (combine with @Required to enforce presence).
 *  - InFuture: reference.before(value)  -> value must be strictly AFTER reference; equal -> FAIL.
 *  - InPast:   reference.after(value)   -> value must be strictly BEFORE reference; equal -> FAIL.
 *  - Both accept java.util.Date and Long (epoch millis). Both also accept LocalDate/LocalDateTime
 *    (PF-145: InPast now mirrors InFuture; LocalDate -> atStartOfDay, LocalDateTime -> atZone,
 *    same strict boundary as the Date path).
 *  - A non-temporal value (e.g. a String) hits the fall-through and returns false.
 */
public class DateChecksTest {

    @InFuture("2020-06-15")
    private Date futureFixed;

    @InPast("2020-06-15")
    private Date pastFixed;

    @InFuture
    private Date futureNow;

    @InPast
    private Date pastNow;

    private static Date date(int year, int month, int day) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month - 1, day, 12, 0, 0); // noon to stay clear of DST/midnight edges
        return cal.getTime();
    }

    /**
     * The frozen reference instant the fixed-annotation checks compare against. This MUST equal
     * what AlternativeDateFormat parses from "2020-06-15" via its "yyyy-MM-dd" pattern, which is
     * local-midnight (00:00:00) — NOT noon. The day-granular {@code date(...)} cases above are a
     * full day away from this instant, so their noon-vs-midnight offset is irrelevant; only the
     * exact-boundary cases need this precise midnight value.
     */
    private static Date reference() {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(2020, Calendar.JUNE, 15, 0, 0, 0); // local midnight, matching the parsed annotation
        return cal.getTime();
    }

    private static InFuture inFutureAnnotation(String field) {
        try {
            return DateChecksTest.class.getDeclaredField(field).getAnnotation(InFuture.class);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static InPast inPastAnnotation(String field) {
        try {
            return DateChecksTest.class.getDeclaredField(field).getAnnotation(InPast.class);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static InFutureCheck inFutureFixed() {
        InFutureCheck c = new InFutureCheck();
        c.configure(inFutureAnnotation("futureFixed"));
        return c;
    }

    private static InPastCheck inPastFixed() {
        InPastCheck c = new InPastCheck();
        c.configure(inPastAnnotation("pastFixed"));
        return c;
    }

    // ---- InFuture against frozen reference 2020-06-15 ----

    @Test
    public void inFutureConfigureParsesFixedReference() {
        assertThat(inFutureFixed().reference).isEqualTo(reference());
    }

    @Test
    public void inFutureNullPasses() {
        assertThat(inFutureFixed().isSatisfied(null, null, null, null)).isTrue();
    }

    @Test
    public void inFutureStrictlyAfterReferencePasses() {
        assertThat(inFutureFixed().isSatisfied(null, date(2020, 6, 16), null, null)).isTrue();
        assertThat(inFutureFixed().isSatisfied(null, date(2030, 1, 1), null, null)).isTrue();
    }

    @Test
    public void inFutureBeforeReferenceFails() {
        assertThat(inFutureFixed().isSatisfied(null, date(2020, 6, 14), null, null)).isFalse();
        assertThat(inFutureFixed().isSatisfied(null, date(2010, 1, 1), null, null)).isFalse();
    }

    @Test
    public void inFutureExactReferenceFailsBoundary() {
        // reference.before(reference) is false -> equal instant is NOT "in the future".
        assertThat(inFutureFixed().isSatisfied(null, reference(), null, null)).isFalse();
    }

    @Test
    public void inFutureAcceptsLongEpochMillis() {
        long after = reference().getTime() + 86_400_000L;  // +1 day
        long before = reference().getTime() - 86_400_000L; // -1 day
        assertThat(inFutureFixed().isSatisfied(null, after, null, null)).isTrue();
        assertThat(inFutureFixed().isSatisfied(null, before, null, null)).isFalse();
    }

    @Test
    public void inFutureNonTemporalValueFails() {
        assertThat(inFutureFixed().isSatisfied(null, "not-a-date", null, null)).isFalse();
    }

    // ---- InPast against frozen reference 2020-06-15 ----

    @Test
    public void inPastConfigureParsesFixedReference() {
        assertThat(inPastFixed().reference).isEqualTo(reference());
    }

    @Test
    public void inPastNullPasses() {
        assertThat(inPastFixed().isSatisfied(null, null, null, null)).isTrue();
    }

    @Test
    public void inPastStrictlyBeforeReferencePasses() {
        assertThat(inPastFixed().isSatisfied(null, date(2020, 6, 14), null, null)).isTrue();
        assertThat(inPastFixed().isSatisfied(null, date(2000, 1, 1), null, null)).isTrue();
    }

    @Test
    public void inPastAfterReferenceFails() {
        assertThat(inPastFixed().isSatisfied(null, date(2020, 6, 16), null, null)).isFalse();
        assertThat(inPastFixed().isSatisfied(null, date(2030, 1, 1), null, null)).isFalse();
    }

    @Test
    public void inPastExactReferenceFailsBoundary() {
        // reference.after(reference) is false -> equal instant is NOT "in the past".
        assertThat(inPastFixed().isSatisfied(null, reference(), null, null)).isFalse();
    }

    @Test
    public void inPastAcceptsLongEpochMillis() {
        long before = reference().getTime() - 86_400_000L;
        long after = reference().getTime() + 86_400_000L;
        assertThat(inPastFixed().isSatisfied(null, before, null, null)).isTrue();
        assertThat(inPastFixed().isSatisfied(null, after, null, null)).isFalse();
    }

    @Test
    public void inPastHandlesLocalDate() {
        // PF-145: InPastCheck now mirrors InFutureCheck — LocalDate is converted via
        // atStartOfDay(systemDefault()) and compared with the SAME strict reference.after(value)
        // boundary as the Date/Long paths.
        // Clearly-past LocalDate -> strictly before reference -> passes.
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDate.of(1990, 1, 1), null, null)).isTrue();
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDate.of(2020, 6, 14), null, null)).isTrue();
        // Clearly-future LocalDate -> after reference -> fails.
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDate.of(2020, 6, 16), null, null)).isFalse();
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDate.of(2030, 1, 1), null, null)).isFalse();
        // Boundary: LocalDate at the reference's local-midnight equals reference; strict .after() -> fails.
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDate.of(2020, 6, 15), null, null)).isFalse();
    }

    @Test
    public void inPastHandlesLocalDateTime() {
        // PF-145: LocalDateTime converted via atZone(systemDefault()); same strict reference.after(value) boundary.
        // Clearly-past -> passes.
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDateTime.of(2020, 6, 14, 12, 0), null, null)).isTrue();
        // Clearly-future -> fails.
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDateTime.of(2020, 6, 16, 12, 0), null, null)).isFalse();
        // Boundary: exactly the reference's local-midnight instant -> equal -> strict .after() -> fails.
        assertThat(inPastFixed().isSatisfied(null, java.time.LocalDateTime.of(2020, 6, 15, 0, 0), null, null)).isFalse();
    }

    // ---- "now"-relative sanity (empty value()) — robust because dates are decades away ----

    @Test
    public void inFutureNowReferenceFarDatesAreDeterministic() {
        InFutureCheck c = new InFutureCheck();
        c.configure(inFutureAnnotation("futureNow"));
        assertThat(c.isSatisfied(null, date(2100, 1, 1), null, null)).isTrue();  // far future
        assertThat(c.isSatisfied(null, date(1900, 1, 1), null, null)).isFalse(); // far past
        assertThat(c.isSatisfied(null, null, null, null)).isTrue();
    }

    @Test
    public void inPastNowReferenceFarDatesAreDeterministic() {
        InPastCheck c = new InPastCheck();
        c.configure(inPastAnnotation("pastNow"));
        assertThat(c.isSatisfied(null, date(1900, 1, 1), null, null)).isTrue();  // far past
        assertThat(c.isSatisfied(null, date(2100, 1, 1), null, null)).isFalse(); // far future
        assertThat(c.isSatisfied(null, null, null, null)).isTrue();
    }
}
