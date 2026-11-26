package play.server;

import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Immutable, thread-safe holder of the configured default security headers, applied at every
 * HTTP response emission site in {@code PlayHandler}. Owned by
 * {@link play.plugins.SecurityHeadersPlugin}: the plugin reads {@code http.headers.*} from
 * {@link play.Play#configuration} on {@code onConfigurationRead} and {@link #install installs}
 * a fresh policy. Server emission paths call {@link #current()} and apply additively — never
 * overwriting an existing header.
 *
 * <p>Hot reload is free: in dev mode {@code ConfigurationChangeWatcherPlugin} re-fires
 * {@code onConfigurationRead} when {@code application.conf} changes, the plugin rebuilds the
 * policy, and a single {@code volatile} swap takes effect for all subsequent responses.
 */
public final class SecurityHeadersPolicy {

    /** No-op policy used when the plugin is absent or {@code http.headers.enabled=false}. */
    public static final SecurityHeadersPolicy DISABLED =
            new SecurityHeadersPolicy(false, Map.of(), false, "");

    private static volatile SecurityHeadersPolicy current = DISABLED;

    public static SecurityHeadersPolicy current() {
        return current;
    }

    public static void install(SecurityHeadersPolicy policy) {
        current = (policy == null) ? DISABLED : policy;
    }

    /**
     * Build a policy from the application configuration. Returns {@link #DISABLED} when the
     * master switch is off; otherwise pre-resolves the static-header map and the HSTS value
     * once so the hot path is just a containment check + map iteration.
     */
    public static SecurityHeadersPolicy fromConfig(Properties config) {
        if (!bool(config, "http.headers.enabled", true)) {
            return DISABLED;
        }

        Map<String, String> staticHeaders = new LinkedHashMap<>();
        addIfConfigured(staticHeaders, config, "http.headers.xContentTypeOptions",
                "X-Content-Type-Options", "nosniff");
        addIfConfigured(staticHeaders, config, "http.headers.xFrameOptions",
                "X-Frame-Options", "DENY");
        addIfConfigured(staticHeaders, config, "http.headers.referrerPolicy",
                "Referrer-Policy", "strict-origin-when-cross-origin");
        addIfConfigured(staticHeaders, config, "http.headers.xXssProtection",
                "X-XSS-Protection", "0");
        addIfConfigured(staticHeaders, config, "http.headers.contentSecurityPolicy",
                "Content-Security-Policy", "default-src 'self'");

        boolean hstsEnabled = bool(config, "http.headers.hsts.enabled", true);
        long maxAge = Long.parseLong(config.getProperty("http.headers.hsts.maxAge", "31536000"));
        boolean includeSubDomains = bool(config, "http.headers.hsts.includeSubDomains", true);
        boolean preload = bool(config, "http.headers.hsts.preload", false);

        StringBuilder hsts = new StringBuilder("max-age=").append(maxAge);
        if (includeSubDomains) hsts.append("; includeSubDomains");
        if (preload) hsts.append("; preload");

        return new SecurityHeadersPolicy(true, staticHeaders, hstsEnabled, hsts.toString());
    }

    private static final AsciiString HSTS = AsciiString.cached("Strict-Transport-Security");

    private final boolean enabled;
    private final Map<String, String> staticHeaders;
    private final boolean hstsEnabled;
    private final String hstsValue;
    /**
     * PF-255: the static headers, names and values, validated once, in configuration order. A
     * response that sets none of them (nearly every one) takes them in one copy, which Netty makes
     * without validating again, since a response's headers and these come from the same
     * {@link DefaultHttpHeadersFactory}. Validating the five names and values on every response,
     * after a {@code contains} with a name hashed each time, was 8.6% of a plain-text request's
     * CPU on its virtual thread.
     */
    private final AsciiString[] names;
    private final AsciiString[] values;
    private final HttpHeaders validated;
    private final AsciiString hstsAscii;

    private SecurityHeadersPolicy(boolean enabled, Map<String, String> staticHeaders,
                                  boolean hstsEnabled, String hstsValue) {
        this.enabled = enabled;
        this.staticHeaders = Map.copyOf(staticHeaders);
        this.hstsEnabled = hstsEnabled;
        this.hstsValue = hstsValue;
        this.names = new AsciiString[staticHeaders.size()];
        this.values = new AsciiString[staticHeaders.size()];
        this.validated = DefaultHttpHeadersFactory.headersFactory().newHeaders();
        int i = 0;
        for (Map.Entry<String, String> e : staticHeaders.entrySet()) {
            names[i] = new AsciiString(e.getKey());
            values[i] = new AsciiString(e.getValue());
            validated.add(names[i], values[i]);
            i++;
        }
        this.hstsAscii = new AsciiString(hstsValue);
    }

    /** Apply policy to a Netty response's headers. Both {@code contains} and {@code set} are
     *  case-insensitive in Netty {@link HttpHeaders}, so existing user-set headers win regardless
     *  of casing. */
    public void applyTo(HttpHeaders nettyHeaders, boolean secure) {
        if (!enabled || nettyHeaders == null) return;
        boolean anySet = false;
        for (AsciiString name : names) {
            if (nettyHeaders.contains(name)) {
                anySet = true;
                break;
            }
        }
        if (!anySet) {
            nettyHeaders.add(validated);
        } else {
            for (int i = 0; i < names.length; i++) {
                if (!nettyHeaders.contains(names[i])) {
                    nettyHeaders.set(names[i], values[i]);
                }
            }
        }
        if (hstsEnabled && secure && !nettyHeaders.contains(HSTS)) {
            nettyHeaders.set(HSTS, hstsAscii);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHstsEnabled() {
        return hstsEnabled;
    }

    public String getHeader(String name) {
        return staticHeaders.get(name);
    }

    public String getHstsValue() {
        return hstsValue;
    }

    private static boolean bool(Properties cfg, String key, boolean def) {
        String v = cfg.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static void addIfConfigured(Map<String, String> map, Properties cfg, String key,
                                        String headerName, String defaultValue) {
        String v = cfg.getProperty(key, defaultValue);
        if (v == null || v.isEmpty() || "disabled".equalsIgnoreCase(v)) return;
        map.put(headerName, v);
    }
}
