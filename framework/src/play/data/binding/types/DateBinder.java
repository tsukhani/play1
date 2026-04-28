package play.data.binding.types;

import play.Play;
import play.data.binding.TypeBinder;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import play.data.binding.AnnotationHelper;
import play.libs.I18N;

/**
 * Binder that support Date class.
 */
public class DateBinder implements TypeBinder<Date> {

    public static final String ISO8601 = "'ISO8601:'yyyy-MM-dd'T'HH:mm:ssZ";

    /**
     * Audit M21: pin the parser timezone explicitly. Without this, user-submitted
     * dates were interpreted in the JVM's default timezone — meaning a date entered
     * by an Asian user could land on a different calendar day than the server stored
     * (off-by-up-to-24h). Affects {@code @InFuture} / {@code @InPast} validators
     * downstream. Default is UTC; configurable via {@code play.date.timezone} for
     * apps that legitimately want server-local interpretation.
     */
    private static TimeZone bindingTimeZone() {
        if (Play.configuration == null) {
            return TimeZone.getTimeZone("UTC");
        }
        String configured = Play.configuration.getProperty("play.date.timezone", "UTC");
        return TimeZone.getTimeZone(configured);
    }

    @Override
    public Date bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) throws Exception {
        if (value == null || value.isBlank()) {
            return null;
        }

        Date date = AnnotationHelper.getDateAs(annotations, value);
        if (date != null) {
            return date;
        }

        TimeZone tz = bindingTimeZone();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(I18N.getDateFormat());
            sdf.setLenient(false);
            sdf.setTimeZone(tz);
            return sdf.parse(value);
        } catch (ParseException e) {
            // Ignore
        }

        SimpleDateFormat sdf = new SimpleDateFormat(ISO8601);
        sdf.setLenient(false);
        // ISO8601 strings carry their own offset (the trailing Z); tz only affects
        // the printed-but-unused form and stays for consistency.
        sdf.setTimeZone(tz);
        return sdf.parse(value);
    }
}
