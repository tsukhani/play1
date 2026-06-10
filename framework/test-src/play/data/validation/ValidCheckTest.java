package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PF-138: ValidCheck — recursive object-graph validation.
 *
 * isSatisfied() runs a fresh oval {@code new Validator().validate(value)} over the nested
 * object's own @Constraint annotations (e.g. @Required, @Min). If the nested object has
 * violations, ValidCheck returns false AND records each violation into
 * {@code Validation.current().errors} with a dotted field key. A null value passes.
 *
 * Setup needs the per-request thread-locals the framework normally sets up:
 *  - Validation.current (so errors can be collected) — via ValidationBuilder.build().
 *  - ValidationPlugin.keys (the identity->key map the check reads/writes) — set here.
 *
 * We pass context == null, so the dotted key prefix is empty and nested field keys are
 * recorded as plain field names (e.g. "name"), which is what we assert.
 */
public class ValidCheckTest {

    /** Nested bean with its own constraints. */
    public static class Address {
        @Required
        public String street;

        Address(String street) {
            this.street = street;
        }
    }

    /** Bean whose 'age' must be >= 18. */
    public static class Person {
        @Required
        public String name;

        @Min(18)
        public int age;

        Person(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }

    private final ValidCheck check = new ValidCheck();

    @BeforeEach
    public void setUp() {
        ValidationBuilder.build();
        ValidationPlugin.keys.set(new HashMap<>());
    }

    private boolean ok(Object value) {
        return check.isSatisfied(null, value, null, null);
    }

    @Test
    public void nullValuePasses() {
        assertThat(ok(null)).isTrue();
        assertThat(Validation.current().errors).isEmpty();
    }

    @Test
    public void validNestedGraphPasses() {
        assertThat(ok(new Person("Alice", 30))).isTrue();
        assertThat(Validation.current().errors).isEmpty();
    }

    @Test
    public void invalidNestedFieldIsDetectedAndRecorded() {
        // age 16 violates @Min(18) -> recursion must FAIL and record the violation.
        boolean result = ok(new Person("Bob", 16));
        assertThat(result).isFalse();

        List<Error> errors = Validation.current().errors;
        assertThat(errors).isNotEmpty();
        List<String> keys = new ArrayList<>();
        for (Error e : errors) {
            keys.add(e.getKey());
        }
        assertThat(keys).contains("age");
    }

    @Test
    public void missingRequiredNestedFieldIsDetected() {
        boolean result = ok(new Person(null, 30)); // name is @Required
        assertThat(result).isFalse();

        List<String> keys = new ArrayList<>();
        for (Error e : Validation.current().errors) {
            keys.add(e.getKey());
        }
        assertThat(keys).contains("name");
    }

    @Test
    public void multipleNestedViolationsAllRecorded() {
        boolean result = ok(new Person(null, 10)); // both name and age invalid
        assertThat(result).isFalse();

        List<String> keys = new ArrayList<>();
        for (Error e : Validation.current().errors) {
            keys.add(e.getKey());
        }
        assertThat(keys).contains("name", "age");
    }

    @Test
    public void collectionOfBeansValidatedElementwise() {
        // A valid collection passes...
        assertThat(ok(new ArrayList<>(Arrays.asList(new Address("Main St"), new Address("2nd Ave"))))).isTrue();

        // ...and a collection containing an invalid element fails (recursion over each item).
        setUp(); // reset error/keys state
        boolean result = ok(new ArrayList<>(Arrays.asList(new Address("Main St"), new Address(null))));
        assertThat(result).isFalse();
        assertThat(Validation.current().errors).isNotEmpty();
    }
}
