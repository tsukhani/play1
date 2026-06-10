package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PF-138: CheckWithCheck — delegates validation to a user-supplied {@link Check} subclass
 * named by the @CheckWith annotation's value().
 *
 * configure() reflectively instantiates that Check (via its no-arg constructor) and wires
 * back-reference {@code check.checkWithCheck = this}; isSatisfied() then simply delegates to
 * {@code check.isSatisfied(validatedObject, value)}.
 *
 * We drive it with a REAL {@link CheckWith} annotation on a fixture field whose value() points
 * at the {@link NonBlankCheck} fixture below, so the reflective instantiation + delegation path
 * is genuinely exercised.
 */
public class CheckWithCheckTest {

    /** Fixture Check: passes only for a non-null, non-blank String. */
    public static class NonBlankCheck extends Check {
        @Override
        public boolean isSatisfied(Object validatedObject, Object value) {
            return value instanceof String && !((String) value).trim().isEmpty();
        }
    }

    @CheckWith(NonBlankCheck.class)
    private String field;

    private static CheckWithCheck configured() {
        try {
            CheckWith annotation = CheckWithCheckTest.class.getDeclaredField("field").getAnnotation(CheckWith.class);
            CheckWithCheck c = new CheckWithCheck();
            c.configure(annotation);
            return c;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void configureInstantiatesAndWiresDelegate() {
        CheckWithCheck c = configured();
        assertThat(c.check).isInstanceOf(NonBlankCheck.class);
        // back-reference wired so the delegate can call setMessage/setVariables
        assertThat(c.check.checkWithCheck).isSameAs(c);
    }

    @Test
    public void delegatesPassResult() {
        assertThat(configured().isSatisfied(null, "value", null, null)).isTrue();
    }

    @Test
    public void delegatesFailResult() {
        assertThat(configured().isSatisfied(null, "   ", null, null)).isFalse();
        assertThat(configured().isSatisfied(null, null, null, null)).isFalse();
        assertThat(configured().isSatisfied(null, 123, null, null)).isFalse();
    }
}
