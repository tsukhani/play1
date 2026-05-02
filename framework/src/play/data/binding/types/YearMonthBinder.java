package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.YearMonth;

public class YearMonthBinder implements TypeBinder<YearMonth> {

    @Override
    public YearMonth bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        return value != null && !value.isBlank() ? YearMonth.parse(value) : null;
    }
}
