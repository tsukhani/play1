package play.plugins;

import play.Play;
import play.PlayPlugin;
import play.mvc.Http;
import play.mvc.results.Result;

/**
 * Plugin that adds default security headers to all HTTP responses.
 *
 * <p>Headers are only set if the application has not already set them,
 * allowing controllers to override any header value.</p>
 *
 * <p>Configuration in application.conf:</p>
 * <pre>
 * # Master switch (default: true)
 * http.headers.enabled=true
 *
 * # Individual headers (set to empty string or "disabled" to skip)
 * http.headers.xContentTypeOptions=nosniff
 * http.headers.xFrameOptions=DENY
 * http.headers.referrerPolicy=strict-origin-when-cross-origin
 * http.headers.xXssProtection=0
 * http.headers.contentSecurityPolicy=default-src 'self'
 *
 * # HSTS (only applied when request is HTTPS)
 * http.headers.hsts.enabled=true
 * http.headers.hsts.maxAge=31536000
 * http.headers.hsts.includeSubDomains=true
 * http.headers.hsts.preload=false
 * </pre>
 */
public class SecurityHeadersPlugin extends PlayPlugin {

    private boolean enabled;
    private String xContentTypeOptions;
    private String xFrameOptions;
    private String referrerPolicy;
    private String xXssProtection;
    private String contentSecurityPolicy;
    private boolean hstsEnabled;
    private long hstsMaxAge;
    private boolean hstsIncludeSubDomains;
    private boolean hstsPreload;

    @Override
    public void onApplicationStart() {
        loadConfiguration();
    }

    void loadConfiguration() {
        enabled = Boolean.parseBoolean(Play.configuration.getProperty("http.headers.enabled", "true"));
        xContentTypeOptions = Play.configuration.getProperty("http.headers.xContentTypeOptions", "nosniff");
        xFrameOptions = Play.configuration.getProperty("http.headers.xFrameOptions", "DENY");
        referrerPolicy = Play.configuration.getProperty("http.headers.referrerPolicy", "strict-origin-when-cross-origin");
        xXssProtection = Play.configuration.getProperty("http.headers.xXssProtection", "0");
        contentSecurityPolicy = Play.configuration.getProperty("http.headers.contentSecurityPolicy", "default-src 'self'");
        hstsEnabled = Boolean.parseBoolean(Play.configuration.getProperty("http.headers.hsts.enabled", "true"));
        hstsMaxAge = Long.parseLong(Play.configuration.getProperty("http.headers.hsts.maxAge", "31536000"));
        hstsIncludeSubDomains = Boolean.parseBoolean(Play.configuration.getProperty("http.headers.hsts.includeSubDomains", "true"));
        hstsPreload = Boolean.parseBoolean(Play.configuration.getProperty("http.headers.hsts.preload", "false"));
    }

    @Override
    public void onActionInvocationResult(Result result) {
        if (!enabled) {
            return;
        }

        Http.Response response = Http.Response.current();
        Http.Request request = Http.Request.current();
        if (response == null) {
            return;
        }

        setHeaderIfConfigured(response, "X-Content-Type-Options", xContentTypeOptions);
        setHeaderIfConfigured(response, "X-Frame-Options", xFrameOptions);
        setHeaderIfConfigured(response, "Referrer-Policy", referrerPolicy);
        setHeaderIfConfigured(response, "X-XSS-Protection", xXssProtection);
        setHeaderIfConfigured(response, "Content-Security-Policy", contentSecurityPolicy);

        if (hstsEnabled && request != null && Boolean.TRUE.equals(request.secure)) {
            StringBuilder hsts = new StringBuilder("max-age=").append(hstsMaxAge);
            if (hstsIncludeSubDomains) {
                hsts.append("; includeSubDomains");
            }
            if (hstsPreload) {
                hsts.append("; preload");
            }
            setHeaderIfNotSet(response, "Strict-Transport-Security", hsts.toString());
        }
    }

    private void setHeaderIfConfigured(Http.Response response, String name, String value) {
        if (value != null && !value.isEmpty() && !"disabled".equalsIgnoreCase(value)) {
            setHeaderIfNotSet(response, name, value);
        }
    }

    private void setHeaderIfNotSet(Http.Response response, String name, String value) {
        if (response.getHeader(name) == null) {
            response.setHeader(name, value);
        }
    }
}
