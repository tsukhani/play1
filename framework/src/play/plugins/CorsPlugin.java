package play.plugins;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.mvc.Http;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Built-in CORS (Cross-Origin Resource Sharing) support (PF-6).
 *
 * <p>Handles preflight OPTIONS requests automatically and adds appropriate CORS
 * headers to all responses when enabled. Disabled by default (opt-in) to avoid
 * breaking existing apps.</p>
 *
 * <p>Configuration in application.conf:</p>
 * <pre>
 * cors.enabled=true                                   # master switch (default: false)
 * cors.allowedOrigins=*                               # comma-separated list, or *
 * cors.allowedMethods=GET,POST,PUT,DELETE,OPTIONS
 * cors.allowedHeaders=Content-Type,Authorization
 * cors.exposedHeaders=                                # comma-separated, default empty
 * cors.allowCredentials=false
 * cors.maxAge=3600                                    # preflight cache, seconds
 * </pre>
 *
 * <p>Coexists with {@link SecurityHeadersPlugin} (PF-5): all header writes go
 * through {@code setHeader}-if-absent so plugin order does not blow away values
 * set by application code or other plugins.</p>
 *
 * <p>Per the CORS spec, {@code Access-Control-Allow-Origin: *} is incompatible
 * with {@code Access-Control-Allow-Credentials: true}. When both are configured
 * the plugin echoes the request's {@code Origin} header instead of {@code *},
 * and logs a warning at boot.</p>
 */
public class CorsPlugin extends PlayPlugin {

    private boolean enabled;
    private Set<String> allowedOrigins;
    private boolean allowAllOrigins;
    private String allowedMethods;
    private String allowedHeaders;
    private String exposedHeaders;
    private boolean allowCredentials;
    private String maxAge;

    @Override
    public void onApplicationStart() {
        loadConfiguration();
    }

    void loadConfiguration() {
        enabled = Boolean.parseBoolean(Play.configuration.getProperty("cors.enabled", "false"));
        String origins = Play.configuration.getProperty("cors.allowedOrigins", "*").trim();
        allowAllOrigins = "*".equals(origins);
        if (!allowAllOrigins) {
            allowedOrigins = Arrays.stream(origins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } else {
            allowedOrigins = Collections.emptySet();
        }
        allowedMethods = Play.configuration.getProperty("cors.allowedMethods", "GET,POST,PUT,DELETE,OPTIONS").trim();
        allowedHeaders = Play.configuration.getProperty("cors.allowedHeaders", "Content-Type,Authorization").trim();
        exposedHeaders = Play.configuration.getProperty("cors.exposedHeaders", "").trim();
        allowCredentials = Boolean.parseBoolean(Play.configuration.getProperty("cors.allowCredentials", "false"));
        maxAge = Play.configuration.getProperty("cors.maxAge", "3600").trim();

        if (enabled && allowAllOrigins && allowCredentials) {
            Logger.warn("CORS: cors.allowedOrigins=* combined with cors.allowCredentials=true is "
                    + "rejected by browsers. Reflecting the request Origin header instead of *.");
        }
    }

    /**
     * Short-circuit OPTIONS preflight requests with a 204 + CORS headers, before
     * the action invoker runs.
     */
    @Override
    public boolean rawInvocation(Http.Request request, Http.Response response) throws Exception {
        if (!enabled) {
            return false;
        }
        if (!"OPTIONS".equalsIgnoreCase(request.method)) {
            return false;
        }

        String origin = getRequestHeader(request, "origin");
        if (origin == null || origin.isEmpty()) {
            return false;
        }

        String resolvedOrigin = resolveOrigin(origin);
        if (resolvedOrigin == null) {
            // Origin not allowed — let the request fall through normally.
            return false;
        }

        response.status = 204;
        response.setHeader("Access-Control-Allow-Origin", resolvedOrigin);
        response.setHeader("Access-Control-Allow-Methods", allowedMethods);

        String requestHeaders = getRequestHeader(request, "access-control-request-headers");
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            response.setHeader("Access-Control-Allow-Headers", allowedHeaders);
        }

        if (!maxAge.isEmpty() && !"0".equals(maxAge)) {
            response.setHeader("Access-Control-Max-Age", maxAge);
        }

        if (allowCredentials) {
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }

        if (!allowAllOrigins || allowCredentials) {
            response.setHeader("Vary", "Origin");
        }

        if (Logger.isDebugEnabled()) {
            Logger.debug("CORS preflight handled for origin: %s, path: %s", origin, request.path);
        }

        return true;
    }

    /**
     * Decorate non-preflight responses with CORS headers.
     */
    @Override
    public void onActionInvocationResult(play.mvc.results.Result result) {
        if (!enabled) {
            return;
        }

        Http.Response response = Http.Response.current();
        Http.Request request = Http.Request.current();
        if (response == null || request == null) {
            return;
        }

        String origin = getRequestHeader(request, "origin");
        if (origin == null || origin.isEmpty()) {
            return;
        }

        String resolvedOrigin = resolveOrigin(origin);
        if (resolvedOrigin == null) {
            return;
        }

        setHeaderIfNotSet(response, "Access-Control-Allow-Origin", resolvedOrigin);

        if (allowCredentials) {
            setHeaderIfNotSet(response, "Access-Control-Allow-Credentials", "true");
        }

        if (exposedHeaders != null && !exposedHeaders.isEmpty()) {
            setHeaderIfNotSet(response, "Access-Control-Expose-Headers", exposedHeaders);
        }

        if (!allowAllOrigins || allowCredentials) {
            appendVaryHeader(response, "Origin");
        }
    }

    /**
     * @return value to set in Access-Control-Allow-Origin, or null if origin not allowed.
     */
    String resolveOrigin(String origin) {
        if (allowAllOrigins) {
            // Spec: when credentials are sent, the wildcard is rejected by browsers;
            // echo the request Origin instead.
            return allowCredentials ? origin : "*";
        }
        if (allowedOrigins.contains(origin)) {
            return origin;
        }
        return null;
    }

    private String getRequestHeader(Http.Request request, String name) {
        if (request.headers == null) {
            return null;
        }
        Http.Header header = request.headers.get(name.toLowerCase());
        return header != null ? header.value() : null;
    }

    private void setHeaderIfNotSet(Http.Response response, String name, String value) {
        if (response.getHeader(name) == null) {
            response.setHeader(name, value);
        }
    }

    private void appendVaryHeader(Http.Response response, String value) {
        String existing = response.getHeader("Vary");
        if (existing == null || existing.isEmpty()) {
            response.setHeader("Vary", value);
        } else if (!existing.toLowerCase().contains(value.toLowerCase())) {
            response.setHeader("Vary", existing + ", " + value);
        }
    }
}
