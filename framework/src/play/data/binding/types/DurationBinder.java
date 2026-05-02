package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Duration;

public class DurationBinder implements TypeBinder<Duration> {

    @Override
    public Duration bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        return value != null && !value.isBlank() ? Duration.parse(value) : null;
    }
}
