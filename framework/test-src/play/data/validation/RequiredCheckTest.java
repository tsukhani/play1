package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PF-138: RequiredCheck — the one check that REJECTS null (unlike most checks, which pass null).
 *
 * Real behaviour read from source:
 *  - null            -> false (invalid).
 *  - String          -> trimmed length > 0 (so "" and "   " are invalid).
 *  - Collection      -> non-empty.
 *  - array           -> non-empty.
 *  - any other Object -> true (presence is enough).
 *  (BinaryField branch needs a real model/upload and is left to integration tests.)
 */
public class RequiredCheckTest {

    private final RequiredCheck check = new RequiredCheck();

    private boolean ok(Object v) {
        return check.isSatisfied(null, v, null, null);
    }

    @Test
    public void nullIsInvalid() {
        assertThat(ok(null)).isFalse();
    }

    @Test
    public void nonBlankStringPasses() {
        assertThat(ok("x")).isTrue();
        assertThat(ok("  x  ")).isTrue();
    }

    @Test
    public void emptyOrWhitespaceStringFails() {
        assertThat(ok("")).isFalse();
        assertThat(ok("   ")).isFalse();
        assertThat(ok("\t\n")).isFalse();
    }

    @Test
    public void emptyCollectionFailsNonEmptyPasses() {
        assertThat(ok(Collections.emptyList())).isFalse();
        List<String> one = new ArrayList<>();
        one.add("a");
        assertThat(ok(one)).isTrue();
    }

    @Test
    public void emptyArrayFailsNonEmptyPasses() {
        assertThat(ok(new int[0])).isFalse();
        assertThat(ok(new int[] {1})).isTrue();
        assertThat(ok(new String[0])).isFalse();
        assertThat(ok(new String[] {"a"})).isTrue();
    }

    @Test
    public void arbitraryNonNullObjectPasses() {
        assertThat(ok(new Object())).isTrue();
        assertThat(ok(42)).isTrue();
        assertThat(ok(Boolean.FALSE)).isTrue(); // presence, not truthiness
    }
}
