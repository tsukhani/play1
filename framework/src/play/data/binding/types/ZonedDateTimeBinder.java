package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.ZonedDateTime;

public class ZonedDateTimeBinder implements TypeBinder<ZonedDateTime> {

    @Override
    public ZonedDateTime bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        return value != null && !value.isBlank() ? ZonedDateTime.parse(value) : null;
    }
}
