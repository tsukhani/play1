package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.OffsetTime;

public class OffsetTimeBinder implements TypeBinder<OffsetTime> {

    @Override
    public OffsetTime bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        return value != null && !value.isBlank() ? OffsetTime.parse(value) : null;
    }
}
