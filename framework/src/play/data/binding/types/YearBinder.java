package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Year;

public class YearBinder implements TypeBinder<Year> {

    @Override
    public Year bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        return value != null && !value.isBlank() ? Year.parse(value) : null;
    }
}
