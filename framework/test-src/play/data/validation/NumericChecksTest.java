package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * PF-138: RangeCheck / MinCheck / MaxCheck.
 *
 * Annotations are built REAL — declared on the fixture fields below and read via
 * reflection — then handed to {@code configure(...)} so the annotation→field wiring
 * (e.g. {@code RangeCheck.configure} reading {@code range.min()/max()}) is exercised too,
 * not just {@code isSatisfied}.
 *
 * Real behaviour read from source:
 *  - null always passes (treated as "not invalid"; pair with @Required to enforce presence).
 *  - String values are parsed via Double.parseDouble; unparseable strings FAIL (return false).
 *  - Non-String/non-Number values FAIL.
 *  - Bounds are INCLUSIVE (>= / <=).
 */
public class NumericChecksTest {

    @Range(min = 1.0, max = 10.0)
    private String rangeField;

    @Min(value = 5.0)
    private String minField;

    @Max(value = 5.0)
    private String maxField;

    private static <A extends java.lang.annotation.Annotation> A annotationOn(String field, Class<A> type) {
        try {
            Field f = NumericChecksTest.class.getDeclaredField(field);
            A a = f.getAnnotation(type);
            if (a == null) {
                throw new IllegalStateException("missing @" + type.getSimpleName() + " on " + field);
            }
            return a;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private RangeCheck range() {
        RangeCheck c = new RangeCheck();
        c.configure(annotationOn("rangeField", Range.class));
        return c;
    }

    private MinCheck min() {
        MinCheck c = new MinCheck();
        c.configure(annotationOn("minField", Min.class));
        return c;
    }

    private MaxCheck max() {
        MaxCheck c = new MaxCheck();
        c.configure(annotationOn("maxField", Max.class));
        return c;
    }

    // ---- Range [1, 10] ----

    @Test
    public void rangeConfigureReadsAnnotation() {
        RangeCheck c = range();
        assertThat(c.min).isEqualTo(1.0);
        assertThat(c.max).isEqualTo(10.0);
    }

    @Test
    public void rangeNullPasses() {
        assertThat(range().isSatisfied(null, null, null, null)).isTrue();
    }

    @Test
    public void rangeInsideWithNumberAndString() {
        assertThat(range().isSatisfied(null, 5, null, null)).isTrue();
        assertThat(range().isSatisfied(null, "5", null, null)).isTrue();
        assertThat(range().isSatisfied(null, 5.5, null, null)).isTrue();
    }

    @Test
    public void rangeBoundariesAreInclusive() {
        assertThat(range().isSatisfied(null, 1, null, null)).isTrue();   // min edge
        assertThat(range().isSatisfied(null, 10, null, null)).isTrue();  // max edge
        assertThat(range().isSatisfied(null, "1", null, null)).isTrue();
        assertThat(range().isSatisfied(null, "10", null, null)).isTrue();
    }

    @Test
    public void rangeJustOutsideFails() {
        assertThat(range().isSatisfied(null, 0.999, null, null)).isFalse();
        assertThat(range().isSatisfied(null, 10.001, null, null)).isFalse();
        assertThat(range().isSatisfied(null, 0, null, null)).isFalse();
        assertThat(range().isSatisfied(null, 11, null, null)).isFalse();
    }

    @Test
    public void rangeUnparseableStringFails() {
        assertThat(range().isSatisfied(null, "abc", null, null)).isFalse();
    }

    @Test
    public void rangeNonNumberObjectFails() {
        assertThat(range().isSatisfied(null, new Object(), null, null)).isFalse();
    }

    @Test
    public void rangeEmptyStringFails() {
        // "" is non-null, not a Number, and Double.parseDouble("") throws -> false.
        assertThat(range().isSatisfied(null, "", null, null)).isFalse();
    }

    // ---- Min 5 ----

    @Test
    public void minConfigureReadsAnnotation() {
        assertThat(min().min).isEqualTo(5.0);
    }

    @Test
    public void minNullPasses() {
        assertThat(min().isSatisfied(null, null, null, null)).isTrue();
    }

    @Test
    public void minBoundaryInclusive() {
        assertThat(min().isSatisfied(null, 5, null, null)).isTrue();
        assertThat(min().isSatisfied(null, "5", null, null)).isTrue();
    }

    @Test
    public void minAboveAndBelow() {
        assertThat(min().isSatisfied(null, 6, null, null)).isTrue();
        assertThat(min().isSatisfied(null, 4.999, null, null)).isFalse();
        assertThat(min().isSatisfied(null, "4", null, null)).isFalse();
    }

    @Test
    public void minUnparseableStringFails() {
        assertThat(min().isSatisfied(null, "x", null, null)).isFalse();
    }

    // ---- Max 5 ----

    @Test
    public void maxConfigureReadsAnnotation() {
        assertThat(max().max).isEqualTo(5.0);
    }

    @Test
    public void maxNullPasses() {
        assertThat(max().isSatisfied(null, null, null, null)).isTrue();
    }

    @Test
    public void maxBoundaryInclusive() {
        assertThat(max().isSatisfied(null, 5, null, null)).isTrue();
        assertThat(max().isSatisfied(null, "5", null, null)).isTrue();
    }

    @Test
    public void maxAboveAndBelow() {
        assertThat(max().isSatisfied(null, 4, null, null)).isTrue();
        assertThat(max().isSatisfied(null, 5.001, null, null)).isFalse();
        assertThat(max().isSatisfied(null, "6", null, null)).isFalse();
    }

    @Test
    public void maxUnparseableStringFails() {
        assertThat(max().isSatisfied(null, "x", null, null)).isFalse();
    }
}
