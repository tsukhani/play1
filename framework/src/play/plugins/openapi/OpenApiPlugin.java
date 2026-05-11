package play.plugins.openapi;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Router;

/**
 * Built-in OpenAPI 3 spec generation (PF-12).
 *
 * <p>Exposes four endpoints under {@code openapi.basePath} (default {@code /@api}):
 * <ul>
 *   <li>{@code /@api} (and {@code /@api/}) — 302 redirect to {@code /@api/docs} (same gating as the spec endpoints)</li>
 *   <li>{@code /@api/openapi.json} — pretty-printed JSON spec (DEV mode by default; PROD opt-in via {@code openapi.publicSpec=true})</li>
 *   <li>{@code /@api/openapi.yaml} — pretty-printed YAML spec (same gating as JSON)</li>
 *   <li>{@code /@api/docs} — Swagger UI loaded from the unpkg CDN (same gating as JSON/YAML)</li>
 * </ul>
 *
 * <p>The spec is generated on-demand from {@link Router#routes} and reflection on
 * controller method signatures. There is no annotation-driven enrichment in this
 * initial cut — schemas degrade gracefully to {@code object} for unknown types.
 *
 * <p>Configuration:
 * <pre>
 * openapi.enabled=true        # master switch (default: true)
 * openapi.basePath=/@api      # path prefix; must start with /
 * openapi.publicSpec=false    # serve /openapi.{json,yaml} and /docs in non-DEV modes (default: false)
 * </pre>
 *
 * <p>Why {@code publicSpec} defaults to false: the spec describes every route + parameter
 * shape the app exposes. Publishing it in production is a deliberate API-contract decision,
 * not the default — most apps don't want their full API surface inventoried by anonymous
 * callers. Teams who do want a public spec set the flag explicitly.
 */
public class OpenApiPlugin extends PlayPlugin {

    static final String DEFAULT_BASE_PATH = "/@api";

    private boolean enabled = true;
    private boolean publicSpec = false;
    private String basePath = DEFAULT_BASE_PATH;

    @Override
    public void onConfigurationRead() {
        enabled = Boolean.parseBoolean(Play.configuration.getProperty("openapi.enabled", "true"));
        if (!enabled) {
            Logger.info("OpenApiPlugin: disabled via openapi.enabled=false; /@api endpoints will not be served.");
            return;
        }
        publicSpec = Boolean.parseBoolean(Play.configuration.getProperty("openapi.publicSpec", "false"));
        String configured = Play.configuration.getProperty("openapi.basePath", DEFAULT_BASE_PATH).trim();
        if (configured.isEmpty() || !configured.startsWith("/")) {
            Logger.warn("OpenApiPlugin: ignoring openapi.basePath=%s (must start with /). Falling back to %s.",
                    configured, DEFAULT_BASE_PATH);
            configured = DEFAULT_BASE_PATH;
        }
        // Strip trailing slash so concatenation is unambiguous.
        if (configured.length() > 1 && configured.endsWith("/")) {
            configured = configured.substring(0, configured.length() - 1);
        }
        basePath = configured;
    }

    @Override
    public boolean rawInvocation(Request request, Response response) throws Exception {
        if (!enabled) {
            return false;
        }
        String path = request.path;
        if (path == null || !path.startsWith(basePath)) {
            return false;
        }
        if (path.equals(basePath + "/openapi.json")) {
            return serveJson(response);
        }
        if (path.equals(basePath + "/openapi.yaml") || path.equals(basePath + "/openapi.yml")) {
            return serveYaml(response);
        }
        if (path.equals(basePath + "/docs") || path.equals(basePath + "/docs/")) {
            return serveDocs(response);
        }
        if (path.equals(basePath) || path.equals(basePath + "/")) {
            if (!specEnabled()) {
                return notFound(response);
            }
            response.status = 302;
            response.setHeader("Location", basePath + "/docs");
            return true;
        }
        return false;
    }

    /** True when the OpenAPI surface (JSON, YAML, and Swagger UI) should be served. DEV mode always, PROD only when opted in. */
    private boolean specEnabled() {
        return Play.mode == Play.Mode.DEV || publicSpec;
    }

    private boolean serveJson(Response response) {
        if (!specEnabled()) {
            return notFound(response);
        }
        OpenAPI spec = buildSpec();
        response.status = 200;
        response.contentType = "application/json";
        response.print(Json.pretty(spec));
        return true;
    }

    private boolean serveYaml(Response response) {
        if (!specEnabled()) {
            return notFound(response);
        }
        OpenAPI spec = buildSpec();
        response.status = 200;
        response.contentType = "application/yaml";
        try {
            response.print(Yaml.pretty(spec));
        } catch (Exception e) {
            // Yaml.pretty may throw a checked exception in older swagger versions.
            Logger.error(e, "OpenApiPlugin: failed to serialize spec as YAML");
            response.status = 500;
            response.contentType = "text/plain";
            response.print("Failed to serialize OpenAPI spec as YAML: " + e.getMessage());
        }
        return true;
    }

    private boolean notFound(Response response) {
        response.status = 404;
        response.contentType = "text/plain";
        response.print("Not Found");
        return true;
    }

    private boolean serveDocs(Response response) {
        if (!specEnabled()) {
            return notFound(response);
        }
        response.status = 200;
        response.contentType = "text/html; charset=utf-8";
        response.print(renderDocsHtml(basePath + "/openapi.json"));
        return true;
    }

    private OpenAPI buildSpec() {
        OpenApiGenerator generator = new OpenApiGenerator(
                Play.classloader != null ? Play.classloader : getClass().getClassLoader(),
                Play.configuration.getProperty("application.name"));
        return generator.generate(Router.routes);
    }

    /**
     * Minimal Swagger UI loader. Pulls the JS/CSS from the unpkg CDN — keeps the
     * framework jar lean and avoids bundling ~3 MB of static assets.
     */
    static String renderDocsHtml(String specUrl) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8"/>
                    <title>API Documentation</title>
                    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
                </head>
                <body>
                <div id="swagger-ui"></div>
                <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                <script>
                    window.ui = SwaggerUIBundle({
                        url: '%s',
                        dom_id: '#swagger-ui',
                        deepLinking: true
                    });
                </script>
                </body>
                </html>
                """.formatted(specUrl);
    }
}
