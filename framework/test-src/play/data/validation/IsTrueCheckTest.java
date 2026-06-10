package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PF-138: IsTrueCheck — "this must be true".
 *
 * Real behaviour read from source:
 *  - null -> false (like Required, null is INVALID here).
 *  - String  -> Boolean.parseBoolean (only "true", case-insensitive, is true; everything else false).
 *  - Number  -> doubleValue() != 0.
 *  - Boolean -> its own value.
 *  - any other type -> false.
 */
public class IsTrueCheckTest {

    private final IsTrueCheck check = new IsTrueCheck();

    private boolean ok(Object v) {
        return check.isSatisfied(null, v, null, null);
    }

    @Test
    public void nullIsInvalid() {
        assertThat(ok(null)).isFalse();
    }

    @Test
    public void booleanValues() {
        assertThat(ok(Boolean.TRUE)).isTrue();
        assertThat(ok(Boolean.FALSE)).isFalse();
    }

    @Test
    public void stringValues() {
        assertThat(ok("true")).isTrue();
        assertThat(ok("TRUE")).isTrue();
        assertThat(ok("false")).isFalse();
        assertThat(ok("yes")).isFalse();   // Boolean.parseBoolean only accepts "true"
        assertThat(ok("1")).isFalse();     // string "1" is not "true"
        assertThat(ok("")).isFalse();
    }

    @Test
    public void numberValues() {
        assertThat(ok(1)).isTrue();
        assertThat(ok(-1)).isTrue();
        assertThat(ok(0.5)).isTrue();
        assertThat(ok(0)).isFalse();
        assertThat(ok(0.0)).isFalse();
    }

    @Test
    public void otherTypesFail() {
        assertThat(ok(new Object())).isFalse();
    }
}
