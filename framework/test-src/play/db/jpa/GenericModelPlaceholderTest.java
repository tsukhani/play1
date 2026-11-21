package play.db.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/**
 * GenericModel's own finders run only when JPAEnhancer has not replaced them: for a class that is no entity, or for a
 * method reference such as {@code Book::findById}. Both compilers name the declaring class, GenericModel, in the
 * reference's method handle, so the call never reaches the entity's enhanced finder, and the entity's type is not in
 * the bytecode for any enhancer to redirect it. The message says both causes and what to write instead.
 */
class GenericModelPlaceholderTest {

    @Test
    void aFinderReachedThroughAMethodReferenceSaysWhyAndWhatToWriteInstead() {
        Function<Object, JPABase> byId = GenericModel::findById;
        LongSupplier count = GenericModel::count;

        assertThatThrownBy(() -> byId.apply(1L))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("@jakarta.persistence.Entity")
            .hasMessageContaining("method reference such as Book::findById")
            .hasMessageContaining("id -> Book.findById(id)");
        assertThatThrownBy(count::getAsLong)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessage(GenericModel.notEnhanced().getMessage());
    }
}
