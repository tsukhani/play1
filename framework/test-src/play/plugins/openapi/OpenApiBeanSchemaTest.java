package play.plugins.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;
import play.mvc.Router;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PF-97 bean recursion. Exercises {@link OpenApiGenerator} against
 * routes whose action methods reference the fixture beans in
 * {@code controllers.OpenApiTestController}.
 *
 * <p>Each bean encountered in a parameter or return type should appear under
 * {@code components/schemas/<simpleName>} and be referenced via {@code $ref}
 * at the call site.
 */
public class OpenApiBeanSchemaTest {

    private final OpenApiGenerator generator =
            new OpenApiGenerator(getClass().getClassLoader(), "Test API", "1.0.0");

    private Router.Route route(String method, String path, String action) {
        var r = new Router.Route();
        r.method = method;
        r.path = path;
        r.action = action;
        return r;
    }

    private Schema<?> requestBodySchema(OpenAPI spec, String path) {
        RequestBody body = spec.getPaths().get(path).getPost().getRequestBody();
        assertThat(body).as("request body for " + path).isNotNull();
        MediaType mt = body.getContent().get("application/json");
        assertThat(mt).as("application/json media type for " + path).isNotNull();
        return mt.getSchema();
    }

    @Test
    public void beanInBodyProducesRefToComponentsSchemas() {
        var routes = List.of(route("POST", "/agents", "OpenApiTestController.postAgent"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bodySchema = requestBodySchema(spec, "/agents");
        assertThat(bodySchema.get$ref()).isEqualTo("#/components/schemas/Agent");

        Schema<?> agent = spec.getComponents().getSchemas().get("Agent");
        assertThat(agent).as("Agent schema must be registered under components").isNotNull();
        assertThat(agent.getProperties()).containsKeys("id", "name");
        assertThat(((Schema<?>) agent.getProperties().get("id")).getType()).isEqualTo("integer");
        assertThat(((Schema<?>) agent.getProperties().get("name")).getType()).isEqualTo("string");
    }

    @Test
    public void listOfBeansBecomesArrayOfRefs() {
        var routes = List.of(route("GET", "/agents", "OpenApiTestController.agentList"));
        OpenAPI spec = generator.generate(routes);

        MediaType mt = spec.getPaths().get("/agents").getGet()
                .getResponses().get("200").getContent().get("application/json");
        assertThat(mt.getSchema()).isInstanceOf(ArraySchema.class);
        Schema<?> items = ((ArraySchema) mt.getSchema()).getItems();
        assertThat(items.get$ref()).isEqualTo("#/components/schemas/Agent");
    }

    @Test
    public void selfReferentialBeanIsFiniteAndUsesRef() {
        var routes = List.of(route("POST", "/nodes", "OpenApiTestController.postNode"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bodySchema = requestBodySchema(spec, "/nodes");
        assertThat(bodySchema.get$ref()).isEqualTo("#/components/schemas/Node");

        Schema<?> node = spec.getComponents().getSchemas().get("Node");
        assertThat(node).isNotNull();
        Schema<?> parentProp = (Schema<?>) node.getProperties().get("parent");
        // The recursive field must be a $ref back to Node, not an inlined copy.
        assertThat(parentProp.get$ref()).isEqualTo("#/components/schemas/Node");
    }

    @Test
    public void beanWithNoPublicFieldsRegistersEmptySchema() {
        var routes = List.of(route("POST", "/empty", "OpenApiTestController.postEmpty"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> empty = spec.getComponents().getSchemas().get("EmptyBean");
        assertThat(empty).isNotNull();
        assertThat(empty.getProperties()).isNullOrEmpty();
    }

    @Test
    public void jpaInternalFieldsAreFiltered() {
        var routes = List.of(route("POST", "/jpa", "OpenApiTestController.postJpaPolluted"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("JpaPolluted");
        assertThat(bean).isNotNull();
        Map<String, Schema> props = bean.getProperties();
        assertThat(props).containsOnlyKeys("real");
        assertThat(props).doesNotContainKeys(
                "_persistence_listener_holder",
                "_persistence_primaryKey",
                "__synthetic",
                "transientField",
                "beansTransientField");
    }

    @Test
    public void fieldSchemaAnnotationEnrichesGeneratedSchema() {
        var routes = List.of(route("POST", "/annotated", "OpenApiTestController.postAnnotated"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("AnnotatedFields");
        Map<String, Schema> props = bean.getProperties();

        assertThat(((Schema<?>) props.get("name")).getDescription())
                .isEqualTo("the agent's display name");
        assertThat(((Schema<?>) props.get("model")).getExample()).isEqualTo("kimi-k2.6");
        assertThat(((Schema<?>) props.get("contact")).getFormat()).isEqualTo("email");
        // Annotation overrides reflection-derived type details: String + format=uri.
        assertThat(((Schema<?>) props.get("homepage")).getType()).isEqualTo("string");
        assertThat(((Schema<?>) props.get("homepage")).getFormat()).isEqualTo("uri");
        assertThat(((Schema<?>) props.get("nickname")).getNullable()).isTrue();
        assertThat(((Schema<?>) props.get("oldField")).getDeprecated()).isTrue();
        assertThat(((Schema<?>) props.get("score")).getMinimum().intValue()).isZero();
        assertThat(((Schema<?>) props.get("score")).getMaximum().intValue()).isEqualTo(100);
        assertThat(((Schema<?>) props.get("code")).getPattern()).isEqualTo("^[A-Z]+$");

        assertThat(bean.getRequired())
                .as("@Schema(required=true) and (requiredMode=REQUIRED) both populate required[]")
                .contains("requiredViaRequired", "requiredViaMode");
    }

    @Test
    public void jsonLibraryAnnotationsHonouredBySimpleName() {
        var routes = List.of(route("POST", "/json", "OpenApiTestController.postJsonAnnotated"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("JsonAnnotated");
        Map<String, Schema> props = bean.getProperties();

        // @JsonIgnore and @Expose(serialize=false) — both fields must be absent.
        assertThat(props).doesNotContainKeys("hiddenByJackson", "hiddenByGson");
        // Jackson rename: @JsonProperty("created_at").
        assertThat(props).containsKey("created_at").doesNotContainKey("createdAt");
        // Gson rename: @SerializedName("updated_at").
        assertThat(props).containsKey("updated_at").doesNotContainKey("updatedAt");
        // @JsonProperty(value="kind", required=true) — appears under "kind" AND required[].
        assertThat(props).containsKey("kind");
        assertThat(bean.getRequired()).contains("kind");
    }

    @Test
    public void simpleNameCollisionFallsBackToCanonicalName() {
        // Order matters: first-registered Foo wins the unqualified key, second
        // disambiguates with its canonical name. Routes are processed in order.
        var routes = List.of(
                route("POST", "/foo", "OpenApiTestController.postFoo"),
                route("POST", "/nested-foo", "OpenApiTestController.postNestedFoo"));
        OpenAPI spec = generator.generate(routes);

        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        assertThat(schemas).containsKey("Foo");
        // The second Foo registered under its canonical name.
        String nestedKey = "controllers.OpenApiTestController.Nested.Foo";
        assertThat(schemas).containsKey(nestedKey);

        // Verify the right Foo is referenced at each endpoint.
        Schema<?> firstBody = requestBodySchema(spec, "/foo");
        Schema<?> nestedBody = requestBodySchema(spec, "/nested-foo");
        assertThat(firstBody.get$ref()).isEqualTo("#/components/schemas/Foo");
        assertThat(nestedBody.get$ref()).isEqualTo("#/components/schemas/" + nestedKey);
    }

    @Test
    public void primitiveAndStringFieldsUseExistingHandling() {
        // Sanity check: bean recursion didn't break the basic schemaFor() output
        // for primitive/String types when they appear as fields.
        var routes = List.of(route("POST", "/agents", "OpenApiTestController.postAgent"));
        OpenAPI spec = generator.generate(routes);
        Schema<?> agent = spec.getComponents().getSchemas().get("Agent");
        assertThat(((Schema<?>) agent.getProperties().get("id")).getType()).isEqualTo("integer");
        assertThat(((Schema<?>) agent.getProperties().get("name")).getType()).isEqualTo("string");
    }
}
