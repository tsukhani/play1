package play.classloading;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fixture for {@link CodeTypeAnnotationsTest} (PF-187).
 *
 * javac writes a {@code TYPE_USE} annotation on a local variable, a cast or an
 * {@code instanceof} into the method's Code attribute as
 * {@code RuntimeVisibleTypeAnnotations} with bytecode offsets. Javassist's gap
 * insertion moves the code but never that attribute, so an enhancer that grows a
 * method leaves those offsets pointing at the wrong bytes.
 *
 * {@link #moved(PropertiesEnhancerFixture)} reads a public field of another class,
 * which PropertiesEnhancer rewrites into a FieldAccessor call (the method grows), and
 * then declares an annotated local. {@link #untouched()} has the same annotations but
 * nothing for an enhancer to rewrite. {@link #annotatedReturn()} carries the annotation
 * on its return type, which lives on the method, not in its Code attribute.
 */
public class CodeTypeAnnotationsFixture {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Nullable {
    }

    public String moved(PropertiesEnhancerFixture other) {
        String name = other.name;
        @Nullable String note = name == null ? null : name.trim();
        Object asObject = (@Nullable Object) note;
        return note + asObject;
    }

    public String untouched() {
        String name = toString();
        @Nullable String note = name.isEmpty() ? null : name.trim();
        Object asObject = (@Nullable Object) note;
        return note + asObject;
    }

    public @Nullable String annotatedReturn() {
        return null;
    }
}
