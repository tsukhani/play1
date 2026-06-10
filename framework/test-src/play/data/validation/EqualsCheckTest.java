package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import net.sf.oval.context.FieldContext;
import org.junit.jupiter.api.Test;

/**
 * PF-138: EqualsCheck — "this field must equal another named field's value".
 *
 * The annotation's value() names the OTHER field. In the field-context path the check
 * resolves the other field by name on the validated object's class and reads its value
 * via reflection, then compares. We drive this with a REAL {@link Equals} annotation
 * (via configure) plus a REAL {@link FieldContext} over a fixture bean, so the
 * field-resolution + reflection path is genuinely exercised.
 *
 * Real behaviour read from source:
 *  - value == null  -> passes iff the other value is also null.
 *  - value != null  -> value.equals(otherValue).
 *  - If the named other field does not exist -> returns false (treated invalid).
 *  - With a null context (no field/param info) otherValue stays null, so a null value
 *    passes and any non-null value fails.
 */
public class EqualsCheckTest {

    /** Fixture: a "password confirmation" style bean. */
    static class Form {
        @Equals("password")
        String passwordConfirm;
        String password;

        Form(String password, String passwordConfirm) {
            this.password = password;
            this.passwordConfirm = passwordConfirm;
        }
    }

    private static EqualsCheck configuredFor(String fieldWithAnnotation) {
        try {
            Field f = Form.class.getDeclaredField(fieldWithAnnotation);
            Equals annotation = f.getAnnotation(Equals.class);
            EqualsCheck c = new EqualsCheck();
            c.configure(annotation);
            return c;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static FieldContext contextFor(String field) {
        try {
            return new FieldContext(Form.class.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void configureReadsOtherFieldName() {
        EqualsCheck c = configuredFor("passwordConfirm");
        assertThat(c.to).isEqualTo("password");
    }

    @Test
    public void matchingValuesPass() {
        Form form = new Form("secret", "secret");
        EqualsCheck c = configuredFor("passwordConfirm");
        assertThat(c.isSatisfied(form, form.passwordConfirm, contextFor("passwordConfirm"), null)).isTrue();
    }

    @Test
    public void differingValuesFail() {
        Form form = new Form("secret", "typo");
        EqualsCheck c = configuredFor("passwordConfirm");
        assertThat(c.isSatisfied(form, form.passwordConfirm, contextFor("passwordConfirm"), null)).isFalse();
    }

    @Test
    public void bothNullPass() {
        Form form = new Form(null, null);
        EqualsCheck c = configuredFor("passwordConfirm");
        assertThat(c.isSatisfied(form, form.passwordConfirm, contextFor("passwordConfirm"), null)).isTrue();
    }

    @Test
    public void valueNullButOtherNonNullFails() {
        Form form = new Form("secret", null);
        EqualsCheck c = configuredFor("passwordConfirm");
        assertThat(c.isSatisfied(form, form.passwordConfirm, contextFor("passwordConfirm"), null)).isFalse();
    }

    @Test
    public void valueNonNullButOtherNullFails() {
        Form form = new Form(null, "secret");
        EqualsCheck c = configuredFor("passwordConfirm");
        assertThat(c.isSatisfied(form, form.passwordConfirm, contextFor("passwordConfirm"), null)).isFalse();
    }

    @Test
    public void unknownOtherFieldReturnsFalse() {
        // configure 'to' to a field that does not exist on Form -> getDeclaredField throws -> false.
        EqualsCheck c = new EqualsCheck();
        c.configure(syntheticEquals("doesNotExist"));
        Form form = new Form("secret", "secret");
        assertThat(c.isSatisfied(form, "secret", contextFor("passwordConfirm"), null)).isFalse();
    }

    @Test
    public void nullContextLeavesOtherValueNull() {
        // With context == null the check never resolves otherValue (stays null):
        // a null value passes, a non-null value fails.
        EqualsCheck c = configuredFor("passwordConfirm");
        assertThat(c.isSatisfied(new Form("x", "x"), null, null, null)).isTrue();

        EqualsCheck c2 = configuredFor("passwordConfirm");
        assertThat(c2.isSatisfied(new Form("x", "x"), "x", null, null)).isFalse();
    }

    /** Build an Equals annotation instance with an arbitrary value() for the unknown-field case. */
    private static Equals syntheticEquals(String value) {
        return new Equals() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return Equals.class;
            }

            @Override
            public String message() {
                return EqualsCheck.mes;
            }

            @Override
            public String value() {
                return value;
            }
        };
    }
}
