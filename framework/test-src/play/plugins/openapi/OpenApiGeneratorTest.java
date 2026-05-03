package play.plugins.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import play.mvc.Router;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenApiGenerator} (PF-12). Exercises the generator directly
 * against hand-built {@link Router.Route} objects — no full Play boot required.
 */
public class OpenApiGeneratorTest {

    private final OpenApiGenerator generator =
            new OpenApiGenerator(getClass().getClassLoader(), "Test API");

    /**
     * Test controller in the {@code controllers} package so the generator's
     * "controllers." resolution prefix finds it. Static methods only, matching
     * Play's standard convention.
     */
    public static class Routes {
        // Route action strings reference controllers.OpenApiTestController which is
        // declared at the bottom of this file.
    }

    private Router.Route route(String method, String path, String action) {
        var r = new Router.Route();
        r.method = method;
        r.path = path;
        r.action = action;
        return r;
    }

    @Test
    public void simpleGetRouteAppearsInPaths() {
        var routes = List.of(route("GET", "/ping", "OpenApiTestController.ping"));
        OpenAPI spec = generator.generate(routes);

        assertThat(spec.getPaths()).containsKey("/ping");
        Operation get = spec.getPaths().get("/ping").getGet();
        assertThat(get).isNotNull();
        assertThat(get.getOperationId()).isEqualTo("OpenApiTestController.ping");
    }

    @Test
    public void pathParameterDetected() {
        var routes = List.of(route("GET", "/users/{id}", "OpenApiTestController.show"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/{id}").getGet();
        assertThat(get).isNotNull();
        assertThat(get.getParameters()).isNotEmpty();
        Parameter idParam = get.getParameters().stream()
                .filter(p -> "id".equals(p.getName()))
                .findFirst().orElse(null);
        assertThat(idParam).as("path parameter 'id' must be present").isNotNull();
        assertThat(idParam.getIn()).isEqualTo("path");
        assertThat(idParam.getRequired()).isTrue();
    }

    @Test
    public void methodMappingExhaustive() {
        var routes = List.of(
                route("GET", "/items", "OpenApiTestController.list"),
                route("POST", "/items", "OpenApiTestController.create"),
                route("PUT", "/items", "OpenApiTestController.update"),
                route("DELETE", "/items", "OpenApiTestController.delete"));
        OpenAPI spec = generator.generate(routes);

        PathItem pi = spec.getPaths().get("/items");
        assertThat(pi).isNotNull();
        assertThat(pi.getGet()).as("GET").isNotNull();
        assertThat(pi.getPost()).as("POST").isNotNull();
        assertThat(pi.getPut()).as("PUT").isNotNull();
        assertThat(pi.getDelete()).as("DELETE").isNotNull();
    }

    @Test
    public void voidReturnTypeProducesGenericResponse() {
        var routes = List.of(route("GET", "/ping", "OpenApiTestController.ping"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/ping").getGet();
        assertThat(get.getResponses()).isNotNull();
        assertThat(get.getResponses().get("200")).isNotNull();
        assertThat(get.getResponses().get("200").getDescription()).isEqualTo("OK");
    }

    @Test
    public void unresolvableActionStillEmitsRouteWithPathParams() {
        var routes = List.of(route("GET", "/missing/{id}", "DoesNotExist.action"));
        OpenAPI spec = generator.generate(routes);

        assertThat(spec.getPaths()).containsKey("/missing/{id}");
        Operation get = spec.getPaths().get("/missing/{id}").getGet();
        assertThat(get).isNotNull();
        assertThat(get.getParameters())
                .extracting(Parameter::getName)
                .contains("id");
    }

    @Test
    public void staticDirRoutesAreSkipped() {
        var routes = new ArrayList<Router.Route>();
        routes.add(route("GET", "/public/", "staticDir:public"));
        routes.add(route("GET", "/ping", "OpenApiTestController.ping"));
        OpenAPI spec = generator.generate(routes);

        assertThat(spec.getPaths()).containsOnlyKeys("/ping");
    }

    @Test
    public void serializesAsValidJson() throws Exception {
        var routes = List.of(
                route("GET", "/ping", "OpenApiTestController.ping"),
                route("GET", "/users/{id}", "OpenApiTestController.show"),
                route("POST", "/items", "OpenApiTestController.create"));
        OpenAPI spec = generator.generate(routes);

        String json = Json.pretty(spec);
        assertThat(json).isNotBlank();
        // Round-trip: parse with Jackson — must not throw.
        JsonNode tree = Json.mapper().readTree(json);
        assertThat(tree.path("openapi").asText()).startsWith("3.");
        assertThat(tree.path("paths").path("/ping")).isNotNull();
    }

    @Test
    public void serializesAsValidYaml() throws Exception {
        var routes = List.of(
                route("GET", "/ping", "OpenApiTestController.ping"),
                route("GET", "/users/{id}", "OpenApiTestController.show"));
        OpenAPI spec = generator.generate(routes);

        String yaml = Yaml.pretty(spec);
        assertThat(yaml).isNotBlank();
        // Round-trip: parse with the same YAML mapper that produced it.
        JsonNode tree = Yaml.mapper().readTree(yaml);
        assertThat(tree.path("openapi").asText()).startsWith("3.");
    }
}
