package play.plugins;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.mvc.Http;
import play.mvc.results.Result;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Plugin that provides built-in CORS (Cross-Origin Resource Sharing) support.
 *
 * <p>Handles preflight OPTIONS requests automatically and adds appropriate
 * CORS headers to all responses when enabled.</p>
 *
 * <p>CORS is disabled by default (opt-in) to avoid breaking existing apps.</p>
 *
 * <p>Configuration in application.conf:</p>
 * <pre>
 * # Master switch (default: false — opt-in)
 * cors.enabled=true
 *
 * # Allowed origins (comma-separated, or * for all)
 * cors.allowedOrigins=*
 *
 * # Allowed HTTP methods (comma-separated)
 * cors.allowedMethods=GET,POST,PUT,DELETE,OPTIONS,PATCH
 *
 * # Allowed request headers (comma-separated)
 * cors.allowedHeaders=Content-Type,Authorization,X-Requested-With
 *
 * # Headers exposed to the browser (comma-separated)
 * cors.exposedHeaders=
 *
 * # Whether to allow credentials (cookies, auth headers)
 * cors.allowCredentials=false
 *
 * # How long (in seconds) browsers should cache preflight results
 * cors.maxAge=3600
 * </pre>
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
        allowedMethods = Play.configuration.getProperty("cors.allowedMethods", "GET,POST,PUT,DELETE,OPTIONS,PATCH").trim();
        allowedHeaders = Play.configuration.getProperty("cors.allowedHeaders", "Content-Type,Authorization,X-Requested-With").trim();
        exposedHeaders = Play.configuration.getProperty("cors.exposedHeaders", "").trim();
        allowCredentials = Boolean.parseBoolean(Play.configuration.getProperty("cors.allowCredentials", "false"));
        maxAge = Play.configuration.getProperty("cors.maxAge", "3600").trim();
    }

    /**
     * Intercept OPTIONS preflight requests before routing.
     * Returns true to short-circuit the request pipeline with a 204 response.
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
            // Origin not allowed — let the request fall through normally
            return false;
        }

        // This is a CORS preflight — respond with 204 and CORS headers
        response.status = 204;
        response.setHeader("Access-Control-Allow-Origin", resolvedOrigin);
        response.setHeader("Access-Control-Allow-Methods", allowedMethods);

        String requestHeaders = getRequestHeader(request, "access-control-request-headers");
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            // Echo back the requested headers if they're within our allowed set,
            // or return our configured allowed headers
            response.setHeader("Access-Control-Allow-Headers", allowedHeaders);
        }

        if (!maxAge.isEmpty() && !"0".equals(maxAge)) {
            response.setHeader("Access-Control-Max-Age", maxAge);
        }

        if (allowCredentials) {
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }

        if (!allowAllOrigins) {
            response.setHeader("Vary", "Origin");
        }

        if (Logger.isDebugEnabled()) {
            Logger.debug("CORS preflight handled for origin: %s, path: %s", origin, request.path);
        }

        return true;
    }

    /**
     * Add CORS headers to regular (non-preflight) responses.
     */
    @Override
    public void onActionInvocationResult(Result result) {
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
            // Not a cross-origin request — no CORS headers needed
            return;
        }

        String resolvedOrigin = resolveOrigin(origin);
        if (resolvedOrigin == null) {
            // Origin not allowed
            return;
        }

        setHeaderIfNotSet(response, "Access-Control-Allow-Origin", resolvedOrigin);

        if (allowCredentials) {
            setHeaderIfNotSet(response, "Access-Control-Allow-Credentials", "true");
        }

        if (exposedHeaders != null && !exposedHeaders.isEmpty()) {
            setHeaderIfNotSet(response, "Access-Control-Expose-Headers", exposedHeaders);
        }

        if (!allowAllOrigins) {
            // When not using wildcard, Vary: Origin is required so caches
            // don't serve a response for one origin to a different origin
            appendVaryHeader(response, "Origin");
        }
    }

    /**
     * Resolves the origin against the allowed origins configuration.
     *
     * @return the origin value to set in Access-Control-Allow-Origin, or null if not allowed
     */
    String resolveOrigin(String origin) {
        if (allowAllOrigins) {
            // When allowCredentials is true, we must echo the origin instead of *
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
