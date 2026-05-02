package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;

public class InstantBinder implements TypeBinder<Instant> {

    @Override
    public Instant bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        return value != null && !value.isBlank() ? Instant.parse(value) : null;
    }
}
