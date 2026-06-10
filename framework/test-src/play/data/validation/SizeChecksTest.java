package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * PF-138: MinSizeCheck / MaxSizeCheck — these measure {@code value.toString().length()},
 * NOT collection size. Annotations built real (declared on fixture fields, read via reflection)
 * and fed to configure() so the annotation→field wiring is covered.
 *
 * Real behaviour read from source:
 *  - null OR empty-string ({@code length()==0}) ALWAYS passes for both checks.
 *  - MinSize: length >= minSize. MaxSize: length <= maxSize. Bounds inclusive.
 */
public class SizeChecksTest {

    @MinSize(value = 3)
    private String minSizeField;

    @MaxSize(value = 5)
    private String maxSizeField;

    private static <A extends java.lang.annotation.Annotation> A annotationOn(String field, Class<A> type) {
        try {
            Field f = SizeChecksTest.class.getDeclaredField(field);
            return f.getAnnotation(type);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private MinSizeCheck minSize() {
        MinSizeCheck c = new MinSizeCheck();
        c.configure(annotationOn("minSizeField", MinSize.class));
        return c;
    }

    private MaxSizeCheck maxSize() {
        MaxSizeCheck c = new MaxSizeCheck();
        c.configure(annotationOn("maxSizeField", MaxSize.class));
        return c;
    }

    // ---- MinSize 3 ----

    @Test
    public void minSizeConfigureReadsAnnotation() {
        assertThat(minSize().minSize).isEqualTo(3);
    }

    @Test
    public void minSizeNullAndEmptyPass() {
        assertThat(minSize().isSatisfied(null, null, null, null)).isTrue();
        assertThat(minSize().isSatisfied(null, "", null, null)).isTrue();
    }

    @Test
    public void minSizeExactBoundaryPasses() {
        assertThat(minSize().isSatisfied(null, "abc", null, null)).isTrue(); // length 3 == min
    }

    @Test
    public void minSizeJustUnderFails() {
        assertThat(minSize().isSatisfied(null, "ab", null, null)).isFalse(); // length 2
    }

    @Test
    public void minSizeLongerPasses() {
        assertThat(minSize().isSatisfied(null, "abcd", null, null)).isTrue();
    }

    @Test
    public void minSizeUsesToStringLength() {
        // A non-String value is measured by its toString() length, not collection size.
        assertThat(minSize().isSatisfied(null, 12345, null, null)).isTrue(); // "12345".length()==5
        assertThat(minSize().isSatisfied(null, 12, null, null)).isFalse();   // "12".length()==2
    }

    // ---- MaxSize 5 ----

    @Test
    public void maxSizeConfigureReadsAnnotation() {
        assertThat(maxSize().maxSize).isEqualTo(5);
    }

    @Test
    public void maxSizeNullAndEmptyPass() {
        assertThat(maxSize().isSatisfied(null, null, null, null)).isTrue();
        assertThat(maxSize().isSatisfied(null, "", null, null)).isTrue();
    }

    @Test
    public void maxSizeExactBoundaryPasses() {
        assertThat(maxSize().isSatisfied(null, "abcde", null, null)).isTrue(); // length 5 == max
    }

    @Test
    public void maxSizeJustOverFails() {
        assertThat(maxSize().isSatisfied(null, "abcdef", null, null)).isFalse(); // length 6
    }

    @Test
    public void maxSizeShorterPasses() {
        assertThat(maxSize().isSatisfied(null, "ab", null, null)).isTrue();
    }
}
