package play.utils;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TimeZone;
import java.util.function.BiFunction;

import play.Play;
import play.mvc.Scope;
import play.vfs.VirtualFile;

import static java.util.Objects.requireNonNull;

/**
 * Generic utils
 */
public class Utils {

    public static <T> String join(Iterable<T> values, String separator) {
        if (values == null) {
            return "";
        }

        StringJoiner joiner = new StringJoiner(separator);
        for (T value : values) {
            joiner.add(String.valueOf(value));
        }

        return joiner.toString();
    }

    public static String join(String[] values, String separator) {
        return (values == null) ? "" : String.join(separator, values);
    }

    public static String join(Annotation[] values, String separator) {
        return (values == null) ? "" : join(Arrays.asList(values), separator);
    }

    public static String getSimpleNames(Annotation[] values) {
        if (values == null) {
            return "";
        }
        return Arrays.stream(values)
                .map(a -> "@" + a.annotationType().getSimpleName())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Get the list of annotations in string
     * 
     * @param values
     *            Annotations to format
     * @return The string representation of the annotations
     * @deprecated Use Utils.join(values, " ");
     */
    @Deprecated
    public static String toString(Annotation[] values) {
        return join(values, " ");
    }

    public static String open(String file, Integer line) {
        if (Play.configuration.containsKey("play.editor")) {
            VirtualFile vfile = VirtualFile.fromRelativePath(file);
            if (vfile != null) {
                return String.format(Play.configuration.getProperty("play.editor"), vfile.getRealFile().getAbsolutePath(), line);
            }
        }
        return null;
    }

    /**
     * for java.util.Map
     */
    public static class Maps {

        private static final BiFunction<String[], String[], String[]> MERGE_MAP_VALUES = (oldValues, newValues) -> {
            String[] merged = new String[oldValues.length + newValues.length];
            System.arraycopy(oldValues, 0, merged, 0, oldValues.length);
            System.arraycopy(newValues, 0, merged, oldValues.length, newValues.length);

            return merged;
        };

        public static void mergeValueInMap(Map<String, String[]> map, String name, String value) {
            map.merge(name, new String[]{ value }, MERGE_MAP_VALUES);
        }

        public static void mergeValueInMap(Map<String, String[]> map, String name, String[] values) {
            map.merge(name, requireNonNull(values), MERGE_MAP_VALUES);
        }

        public static <K, V> Map<K, V> filterMap(Map<K, V> map, String keypattern) {
            try {
                @SuppressWarnings("unchecked")
                Map<K, V> filtered = map.getClass().getDeclaredConstructor().newInstance();
                for (Map.Entry<K, V> entry : map.entrySet()) {
                    K key = entry.getKey();
                    if (key.toString().matches(keypattern)) {
                        filtered.put(key, entry.getValue());
                    }
                }
                return filtered;
            } catch (Exception iex) {
                return null;
            }
        }
    }

    // RFC 1123 date formatter, immutable and thread-safe — preferred over the legacy
    // SimpleDateFormat path. ThreadLocal<SimpleDateFormat> caches scale poorly with virtual
    // threads (each task allocates its own), so we share a single DateTimeFormatter.
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    /**
     * Format a Date as an RFC 1123 HTTP date (e.g. {@code "Sun, 06 Nov 1994 08:49:37 GMT"}).
     * Thread-safe; preferred over {@link #getHttpDateFormatter()} for new code.
     */
    public static String formatHttpDate(Date date) {
        return HTTP_DATE.format(date.toInstant());
    }

    /**
     * Parse an RFC 1123 HTTP date string. Falls back to lenient SimpleDateFormat parsing on
     * format mismatches so legacy headers ({@code "Sun, 06-Nov-1994 08:49:37 GMT"}) are still
     * handled.
     */
    public static Date parseHttpDate(String value) throws ParseException {
        try {
            return Date.from(ZonedDateTime.parse(value, HTTP_DATE).toInstant());
        } catch (DateTimeParseException e) {
            // Fall back to the legacy SimpleDateFormat for non-RFC-1123 inputs.
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
            return fmt.parse(value);
        }
    }

    /**
     * @deprecated Allocates a fresh {@link SimpleDateFormat} per call so it is safe to use
     * from any thread. New code should call {@link #formatHttpDate(Date)} or
     * {@link #parseHttpDate(String)} which use the shared immutable formatter.
     */
    @Deprecated
    public static SimpleDateFormat getHttpDateFormatter() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format;
    }

    public static Map<String, String[]> filterMap(Map<String, String[]> map, String prefix) {
        prefix += '.';
        Map<String, String[]> newMap = new HashMap<>(map.size());
        for (String key : map.keySet()) {
            if (!key.startsWith(prefix)) {
                newMap.put(key, map.get(key));
            }
        }
        return newMap;
    }

    public static Map<String, String> filterParams(Scope.Params params, String prefix) {
        return filterParams(params.all(), prefix);
    }

    public static Map<String, String> filterParams(Map<String, String[]> params, String prefix, String separator) {
        Map<String, String> filteredMap = new LinkedHashMap<>();
        prefix += '.';
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                filteredMap.put(e.getKey().substring(prefix.length()), Utils.join(e.getValue(), separator));
            }
        }
        return filteredMap;
    }

    public static Map<String, String> filterParams(Map<String, String[]> params, String prefix) {
        return filterParams(params, prefix, ", ");
    }

    public static void kill(String pid) throws Exception {
        // Validate pid: pids are positive integers. Reject anything else to prevent argument
        // injection on Windows where the original code passed a single concatenated string.
        if (pid == null || !pid.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid pid: " + pid);
        }
        ProcessBuilder pb = OS.isWindows()
                ? new ProcessBuilder("taskkill", "/F", "/PID", pid)
                : new ProcessBuilder("kill", pid);
        // Merge stderr into stdout so a single drain prevents the child blocking on a full pipe.
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        p.waitFor();
    }

    public static class AlternativeDateFormat {

        final List<SimpleDateFormat> formats = new ArrayList<>();
        final Locale locale;

        public AlternativeDateFormat(Locale locale, String... alternativeFormats) {
            this.locale = locale;
            setFormats(alternativeFormats);
        }

        public void setFormats(String... alternativeFormats) {
            for (String format : alternativeFormats) {
                formats.add(new SimpleDateFormat(format, locale));
            }
        }

        // SimpleDateFormat.parse is not thread-safe, so guard the iteration. With Java 25+ as
        // our minimum runtime, JEP 491 guarantees synchronized does not pin virtual threads,
        // so a single shared instance under a monitor is fine — and avoids the per-thread
        // AlternativeDateFormat allocation a ThreadLocal would force on every virtual thread.
        public synchronized Date parse(String source) throws ParseException {
            for (SimpleDateFormat dateFormat : formats) {
                if (source.length() == dateFormat.toPattern().replace("'", "").length()) {
                    try {
                        return dateFormat.parse(source);
                    } catch (ParseException ex) {
                    }
                }
            }
            throw new ParseException("Date format not understood", 0);
        }

        // Single shared instance (was ThreadLocal). The synchronized parse() above keeps
        // it thread-safe; sharing avoids one AlternativeDateFormat allocation per virtual
        // thread on every validation call.
        private static final AlternativeDateFormat DEFAULT = new AlternativeDateFormat(
                Locale.US,
                "yyyy-MM-dd'T'HH:mm:ss'Z'", // ISO8601 + timezone
                "yyyy-MM-dd'T'HH:mm:ss", // ISO8601
                "yyyy-MM-dd HH:mm:ss",
                "yyyyMMdd HHmmss",
                "yyyy-MM-dd",
                "yyyyMMdd'T'HHmmss",
                "yyyyMMddHHmmss",
                "dd'/'MM'/'yyyy",
                "dd-MM-yyyy",
                "dd'/'MM'/'yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm:ss",
                "ddMMyyyy HHmmss",
                "ddMMyyyy"
        );

        public static AlternativeDateFormat getDefaultFormatter() {
            return DEFAULT;
        }
    }

    public static String urlDecodePath(String enc) {
        try {
            return URLDecoder.decode(enc.replaceAll("\\+", "%2B"), Play.defaultWebEncoding);
        } catch (Exception e) {
            return enc;
        }
    }

    public static String urlEncodePath(String plain) {
        try {
            return URLEncoder.encode(plain, Play.defaultWebEncoding);
        } catch (Exception e) {
            return plain;
        }
    }
}
