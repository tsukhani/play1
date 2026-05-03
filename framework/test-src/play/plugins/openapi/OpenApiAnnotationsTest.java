package play.plugins.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.junit.jupiter.api.Test;
import play.mvc.Router;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PF-81 annotation-driven schema enrichment of the OpenAPI 3 spec
 * generator. Exercises {@link OpenApiAnnotationReader} indirectly via
 * {@link OpenApiGenerator#generate(java.util.List)}.
 *
 * <p>Annotation-supplied values are expected to win over inferred values; tags
 * are unioned across class-level {@code @Tag} and method-level
 * {@code @Operation(tags=...)} (the class tag is the controller-wide grouping).
 *
 * <p>Routes whose action method has no Swagger annotations must produce
 * identical output to PF-12's inference-only path (verified by
 * {@link #unannotatedRouteUnchanged()}).
 */
public class OpenApiAnnotationsTest {

    private final OpenApiGenerator generator =
            new OpenApiGenerator(getClass().getClassLoader(), "Test API");

    private Router.Route route(String method, String path, String action) {
        var r = new Router.Route();
        r.method = method;
        r.path = path;
        r.action = action;
        return r;
    }

    @Test
    public void operationSummaryFromAnnotation() {
        var routes = List.of(route("GET", "/users/{id}", "OpenApiTestController.annotatedSummary"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/{id}").getGet();
        assertThat(get.getSummary()).isEqualTo("Fetch user");
    }

    @Test
    public void operationDescriptionFromAnnotation() {
        var routes = List.of(route("GET", "/users/{id}", "OpenApiTestController.annotatedDescription"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/{id}").getGet();
        assertThat(get.getDescription()).isEqualTo("Returns the canonical user record.");
    }

    @Test
    public void apiResponseAnnotationProducesNon200Response() {
        var routes = List.of(route("GET", "/users/{id}", "OpenApiTestController.annotatedNotFound"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/{id}").getGet();
        ApiResponse notFound = get.getResponses().get("404");
        assertThat(notFound).as("404 response must be present").isNotNull();
        assertThat(notFound.getDescription()).isEqualTo("Not found");
    }

    @Test
    public void multipleApiResponsesViaAnnotation() {
        var routes = List.of(route("GET", "/users/{id}", "OpenApiTestController.annotatedMultipleResponses"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/{id}").getGet();
        assertThat(get.getResponses()).containsKey("200");
        assertThat(get.getResponses()).containsKey("404");
        assertThat(get.getResponses().get("200").getDescription()).isEqualTo("Found");
        assertThat(get.getResponses().get("404").getDescription()).isEqualTo("Not found");
    }

    @Test
    public void requestBodyAnnotationOverridesInferredFormBody() {
        var routes = List.of(route("POST", "/users", "OpenApiTestController.annotatedRequestBody"));
        OpenAPI spec = generator.generate(routes);

        Operation post = spec.getPaths().get("/users").getPost();
        RequestBody body = post.getRequestBody();
        assertThat(body).isNotNull();
        assertThat(body.getContent()).isNotNull();
        // The annotation declares a typed JSON body via @Content + @Schema(implementation=User.class).
        MediaType mt = body.getContent().get("application/json");
        assertThat(mt).as("annotation-declared application/json media type").isNotNull();
        assertThat(mt.getSchema()).as("annotation-declared schema").isNotNull();
    }

    @Test
    public void parameterAnnotationOverridesInferredParameter() {
        var routes = List.of(route("GET", "/users/{id}", "OpenApiTestController.annotatedParameter"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/{id}").getGet();
        Parameter idParam = get.getParameters().stream()
                .filter(p -> "id".equals(p.getName()))
                .findFirst().orElseThrow();
        assertThat(idParam.getDescription()).isEqualTo("user id");
        // Inferred path-param "in" is preserved (annotation didn't override it).
        assertThat(idParam.getIn()).isEqualTo("path");
        assertThat(idParam.getRequired()).isTrue();
    }

    @Test
    public void classLevelTagCascadesToAllOperations() {
        var routes = List.of(
                route("GET", "/users/plain", "OpenApiTaggedTestController.plain"),
                route("GET", "/users/method-tagged", "OpenApiTaggedTestController.taggedByMethod"));
        OpenAPI spec = generator.generate(routes);

        Operation plain = spec.getPaths().get("/users/plain").getGet();
        Operation methodTagged = spec.getPaths().get("/users/method-tagged").getGet();
        assertThat(plain.getTags()).contains("users");
        assertThat(methodTagged.getTags()).contains("users");
    }

    @Test
    public void methodLevelTagsUnionWithClassLevel() {
        var routes = List.of(
                route("GET", "/users/method-tagged", "OpenApiTaggedTestController.taggedByMethod"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/method-tagged").getGet();
        assertThat(get.getTags())
                .as("class @Tag + method @Operation(tags) — union, deduped")
                .contains("users", "admin")
                .doesNotHaveDuplicates();
    }

    @Test
    public void unannotatedRouteUnchanged() {
        // Same setup as OpenApiGeneratorTest.pathParameterDetected — must
        // produce identical inferred output when the action has no Swagger
        // annotations.
        var routes = List.of(route("GET", "/users/{id}", "OpenApiTestController.show"));
        OpenAPI spec = generator.generate(routes);

        Operation get = spec.getPaths().get("/users/{id}").getGet();
        assertThat(get).isNotNull();
        assertThat(get.getSummary()).isEqualTo("GET /users/{id}");
        assertThat(get.getDescription()).isNull();
        assertThat(get.getOperationId()).isEqualTo("OpenApiTestController.show");
        // Tag derived from controller name only — no class-level @Tag on this controller.
        assertThat(get.getTags()).containsExactly("OpenApiTestController");
        assertThat(get.getResponses()).containsOnlyKeys("200");
        Parameter idParam = get.getParameters().stream()
                .filter(p -> "id".equals(p.getName()))
                .findFirst().orElseThrow();
        assertThat(idParam.getDescription()).isNull();
        assertThat(idParam.getIn()).isEqualTo("path");
    }
}
