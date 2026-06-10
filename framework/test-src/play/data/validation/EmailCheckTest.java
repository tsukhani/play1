package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PF-138: EmailCheck — regex-based email validation.
 *
 * Real behaviour read from source:
 *  - Runs value through Validation.willBeValidated first (no-op with the default empty
 *    PluginCollection in a unit test, so the value is unchanged).
 *  - null OR empty string passes (combine with @Required to enforce presence).
 *  - Otherwise the whole string must match the bundled email pattern.
 *  - The pattern requires a dotted domain (TLD), so "user@localhost" FAILS.
 */
public class EmailCheckTest {

    private final EmailCheck check = new EmailCheck();

    private boolean ok(String v) {
        return check.isSatisfied(null, v, null, null);
    }

    @Test
    public void nullAndEmptyPass() {
        assertThat(ok(null)).isTrue();
        assertThat(ok("")).isTrue();
    }

    @Test
    public void validAddressesPass() {
        assertThat(ok("user@example.com")).isTrue();
        assertThat(ok("first.last@sub.example.co.uk")).isTrue();
        assertThat(ok("user+tag@example.com")).isTrue();
        assertThat(ok("user_name@example-domain.com")).isTrue();
    }

    @Test
    public void invalidAddressesFail() {
        assertThat(ok("plainstring")).isFalse();
        assertThat(ok("@example.com")).isFalse();
        assertThat(ok("user@")).isFalse();
        assertThat(ok("user @example.com")).isFalse();
        assertThat(ok("user@example")).isFalse(); // no dotted TLD
    }

    @Test
    public void domainWithoutTldFails() {
        assertThat(ok("user@localhost")).isFalse();
    }
}
