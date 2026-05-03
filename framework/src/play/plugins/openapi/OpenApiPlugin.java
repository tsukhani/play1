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
 * <p>Exposes three endpoints under {@code openapi.basePath} (default {@code /@api}):
 * <ul>
 *   <li>{@code /@api/openapi.json} — pretty-printed JSON spec</li>
 *   <li>{@code /@api/openapi.yaml} — pretty-printed YAML spec</li>
 *   <li>{@code /@api/docs} — Swagger UI loaded from the unpkg CDN (DEV mode only)</li>
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
 * </pre>
 */
public class OpenApiPlugin extends PlayPlugin {

    static final String DEFAULT_BASE_PATH = "/@api";

    private boolean enabled = true;
    private String basePath = DEFAULT_BASE_PATH;

    @Override
    public void onConfigurationRead() {
        enabled = Boolean.parseBoolean(Play.configuration.getProperty("openapi.enabled", "true"));
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
        return false;
    }

    private boolean serveJson(Response response) {
        OpenAPI spec = buildSpec();
        response.status = 200;
        response.contentType = "application/json";
        response.print(Json.pretty(spec));
        return true;
    }

    private boolean serveYaml(Response response) {
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

    private boolean serveDocs(Response response) {
        if (Play.mode != Play.Mode.DEV) {
            response.status = 404;
            response.contentType = "text/plain";
            response.print("Not Found");
            return true;
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
     * framework jar lean and avoids bundling ~3 MB of static assets. DEV mode only.
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
