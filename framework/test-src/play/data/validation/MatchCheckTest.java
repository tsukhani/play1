package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PF-138: MatchCheck — value must fully match a configured regex.
 *
 * The annotation's value() is the regex; configure() compiles it. We use a REAL {@link Match}
 * annotation declared on a fixture field so configure() is exercised.
 *
 * Real behaviour read from source:
 *  - null OR empty string passes (combine with @Required to enforce presence).
 *  - Otherwise uses Matcher.matches() — the WHOLE string must match (anchored), not a substring.
 */
public class MatchCheckTest {

    @Match("[a-z]+")
    private String lettersField;

    private static MatchCheck lettersCheck() {
        try {
            Match annotation = MatchCheckTest.class.getDeclaredField("lettersField").getAnnotation(Match.class);
            MatchCheck c = new MatchCheck();
            c.configure(annotation);
            return c;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean ok(String v) {
        return lettersCheck().isSatisfied(null, v, null, null);
    }

    @Test
    public void configureCompilesPattern() {
        assertThat(lettersCheck().pattern.pattern()).isEqualTo("[a-z]+");
    }

    @Test
    public void nullAndEmptyPass() {
        assertThat(ok(null)).isTrue();
        assertThat(ok("")).isTrue();
    }

    @Test
    public void fullyMatchingPasses() {
        assertThat(ok("abc")).isTrue();
    }

    @Test
    public void nonMatchingFails() {
        assertThat(ok("ABC")).isFalse();
        assertThat(ok("123")).isFalse();
    }

    @Test
    public void partialMatchFailsBecauseAnchored() {
        // matches() is anchored: a leading/trailing non-matching char fails the whole match.
        assertThat(ok("abc1")).isFalse();
        assertThat(ok("1abc")).isFalse();
    }
}
