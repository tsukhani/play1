package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * PF-138: UniqueCheck.
 *
 * HONEST SCOPE NOTE: UniqueCheck's real work is a JPQL {@code SELECT COUNT(o) ...} executed
 * through {@code Model.Manager.factoryFor(...)} / {@code JPQL.instance.count(...)} against a
 * live persistence context. That path cannot be exercised as a plain unit test without a booted
 * DB + JPA — it is integration-scope (a booted-DB functional test, not covered here). We do NOT
 * fake the DB.
 *
 * What IS unit-testable is the early-return contract: a null value is always satisfied
 * (the uniqueness query is skipped entirely). configure() reading the annotation's value()
 * (the extra unique-key context) is also covered.
 */
public class UniqueCheckTest {

    @Unique("tenantId")
    private String email;

    private static UniqueCheck configured() {
        try {
            Field f = UniqueCheckTest.class.getDeclaredField("email");
            Unique annotation = f.getAnnotation(Unique.class);
            UniqueCheck c = new UniqueCheck();
            c.configure(annotation);
            return c;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void nullValueIsAlwaysSatisfied() {
        // value == null returns true before touching any context/model/DB, so passing
        // null for validatedObject/context/validator is safe here.
        assertThat(configured().isSatisfied(null, null, null, null)).isTrue();
    }

    @Test
    public void configureReadsUniqueKeyContext() throws Exception {
        UniqueCheck c = configured();
        Field ctx = UniqueCheck.class.getDeclaredField("uniqueKeyContext");
        ctx.setAccessible(true);
        assertThat(ctx.get(c)).isEqualTo("tenantId");
    }
}
