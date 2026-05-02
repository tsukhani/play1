package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;

public class OffsetDateTimeBinder implements TypeBinder<OffsetDateTime> {

    @Override
    public OffsetDateTime bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        return value != null && !value.isBlank() ? OffsetDateTime.parse(value) : null;
    }
}
