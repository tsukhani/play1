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

    // -- PF-98 getter reflection --------------------------------------------

    @Test
    public void interfaceGettersPopulateSchema() {
        var routes = List.of(route("POST", "/uploads", "OpenApiTestController.postUploadLike"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> upload = spec.getComponents().getSchemas().get("UploadLike");
        assertThat(upload).as("interface schema must be registered").isNotNull();
        Map<String, Schema> props = upload.getProperties();
        assertThat(props).containsKeys("contentType", "fileName", "size", "finished");
        assertThat(((Schema<?>) props.get("size")).getType()).isEqualTo("integer");
        assertThat(((Schema<?>) props.get("finished")).getType()).isEqualTo("boolean");
    }

    @Test
    public void javaBeanGettersOnClassPopulateSchema() {
        var routes = List.of(route("POST", "/jb", "OpenApiTestController.postJavaBeanish"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> jb = spec.getComponents().getSchemas().get("JavaBeanish");
        assertThat(jb.getProperties()).containsKeys("id", "name");
        assertThat(((Schema<?>) jb.getProperties().get("id")).getType()).isEqualTo("integer");
        assertThat(((Schema<?>) jb.getProperties().get("name")).getType()).isEqualTo("string");
    }

    @Test
    public void acronymGetterNamePreservesCasing() {
        var routes = List.of(route("POST", "/acr", "OpenApiTestController.postWithAcronym"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("WithAcronym");
        // Introspector.decapitalize preserves an all-uppercase first two chars.
        assertThat(bean.getProperties()).containsKey("URL").doesNotContainKey("uRL");
    }

    @Test
    public void isAccessorProducesBooleanProperty() {
        var routes = List.of(route("POST", "/isacc", "OpenApiTestController.postWithIsAccessor"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("WithIsAccessor");
        assertThat(bean.getProperties()).containsKey("active");
        assertThat(((Schema<?>) bean.getProperties().get("active")).getType()).isEqualTo("boolean");
    }

    @Test
    public void objectInheritedGettersAreExcluded() {
        var routes = List.of(route("POST", "/jb", "OpenApiTestController.postJavaBeanish"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("JavaBeanish");
        // getClass() is on every Java class but must not appear as a `class` property.
        assertThat(bean.getProperties()).doesNotContainKey("class");
    }

    @Test
    public void fieldWinsOverGetterWithSameName() {
        var routes = List.of(route("POST", "/fag", "OpenApiTestController.postFieldAndGetter"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("FieldAndGetter");
        // Exactly one `name` property, not two.
        assertThat(bean.getProperties()).hasSize(1).containsKey("name");
    }

    @Test
    public void inheritedGettersAppearInSubclassSchema() {
        var routes = List.of(route("POST", "/child", "OpenApiTestController.postGetterChild"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("GetterChild");
        assertThat(bean.getProperties()).containsKeys("inherited", "own");
    }

    @Test
    public void getterAnnotationsAreHonoured() {
        var routes = List.of(route("POST", "/ag", "OpenApiTestController.postAnnotatedGetters"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("AnnotatedGetters");
        Map<String, Schema> props = bean.getProperties();

        // @JsonIgnore on getter: omitted.
        assertThat(props).doesNotContainKeys("hidden", "Hidden");
        // @JsonProperty rename on getter: renamed key, original key absent.
        assertThat(props).containsKey("created_at").doesNotContainKey("createdAt");
        // @Schema enrichment on getter: description + example.
        assertThat(((Schema<?>) props.get("label")).getDescription()).isEqualTo("the canonical label");
        assertThat(((Schema<?>) props.get("label")).getExample()).isEqualTo("hello");
        // @JsonProperty(required=true) on getter: name appears in required[].
        assertThat(props).containsKey("kind");
        assertThat(bean.getRequired()).contains("kind");
    }

    // -- PF-99 record handling ----------------------------------------------

    @Test
    public void plainRecordProducesComponentForEachComponent() {
        var routes = List.of(route("POST", "/r/agent", "OpenApiTestController.postRecAgent"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> rec = spec.getComponents().getSchemas().get("RecAgent");
        Map<String, Schema> props = rec.getProperties();
        assertThat(props).containsOnlyKeys("id", "name", "enabled");
        assertThat(((Schema<?>) props.get("id")).getType()).isEqualTo("integer");
        assertThat(((Schema<?>) props.get("name")).getType()).isEqualTo("string");
        assertThat(((Schema<?>) props.get("enabled")).getType()).isEqualTo("boolean");
    }

    @Test
    public void recordComponentPreservesParameterizedTypes() {
        var routes = List.of(route("POST", "/r/page", "OpenApiTestController.postRecPage"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> rec = spec.getComponents().getSchemas().get("RecPage");
        Map<String, Schema> props = rec.getProperties();
        assertThat(props.get("items")).isInstanceOf(ArraySchema.class);
        assertThat(((Schema<?>) ((ArraySchema) props.get("items")).getItems()).getType()).isEqualTo("integer");
        assertThat(((Schema<?>) props.get("total")).getType()).isEqualTo("integer");
    }

    @Test
    public void selfReferentialRecordIsFinite() {
        var routes = List.of(route("POST", "/r/tree", "OpenApiTestController.postRecTree"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> rec = spec.getComponents().getSchemas().get("RecTree");
        // children is List<RecTree> — must be an ArraySchema of $ref to RecTree.
        Schema<?> children = (Schema<?>) rec.getProperties().get("children");
        assertThat(children).isInstanceOf(ArraySchema.class);
        Schema<?> items = ((ArraySchema) children).getItems();
        assertThat(items.get$ref()).isEqualTo("#/components/schemas/RecTree");
    }

    @Test
    public void recordComponentTypedAsAnotherRecordRecursesProperly() {
        var routes = List.of(route("POST", "/r/owner", "OpenApiTestController.postRecOwner"));
        OpenAPI spec = generator.generate(routes);

        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        assertThat(schemas).containsKeys("RecOwner", "RecAgent");
        Schema<?> owner = schemas.get("RecOwner");
        Schema<?> ownerField = (Schema<?>) owner.getProperties().get("owner");
        assertThat(ownerField.get$ref()).isEqualTo("#/components/schemas/RecAgent");
    }

    @Test
    public void recordComponentAnnotationsOnConstructorParam() {
        var routes = List.of(route("POST", "/r/ann", "OpenApiTestController.postRecAnnotated"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> rec = spec.getComponents().getSchemas().get("RecAnnotated");
        Map<String, Schema> props = rec.getProperties();

        // @Schema(description=...) — enrichment.
        assertThat(((Schema<?>) props.get("id")).getDescription()).isEqualTo("the entry id");
        // @JsonProperty rename.
        assertThat(props).containsKey("created_at").doesNotContainKey("createdAt");
        // @JsonIgnore — component absent.
        assertThat(props).doesNotContainKey("hidden");
        // @JsonProperty(required=true) — appears under renamed key AND required[].
        assertThat(props).containsKey("kind");
        assertThat(rec.getRequired()).contains("kind");
    }

    @Test
    public void recordComponentAnnotationsOnAccessor() {
        var routes = List.of(route("POST", "/r/acc", "OpenApiTestController.postRecAccessorAnnotated"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> rec = spec.getComponents().getSchemas().get("RecAccessorAnnotated");
        Schema<?> valueProp = (Schema<?>) rec.getProperties().get("value");
        assertThat(valueProp.getDescription()).isEqualTo("annotated via accessor");
    }

    @Test
    public void sameSimpleNameRecordsDisambiguateByCanonicalName() {
        var routes = List.of(
                route("POST", "/r/dup", "OpenApiTestController.postRecDup"),
                route("POST", "/r/dup-nested", "OpenApiTestController.postRecDupNested"));
        OpenAPI spec = generator.generate(routes);

        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        assertThat(schemas).containsKey("RecDup");
        assertThat(schemas).containsKey("controllers.OpenApiTestController.Bag.RecDup");
    }

    // -- PF-100 stdlib types, Optional, Map ---------------------------------

    @Test
    public void stdlibTypesMapToTypedStringsAndNumbers() {
        var routes = List.of(route("POST", "/stdlib", "OpenApiTestController.postStdlib"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("StdlibBean");
        Map<String, Schema> p = bean.getProperties();

        assertThat(((Schema<?>) p.get("created")).getType()).isEqualTo("string");
        assertThat(((Schema<?>) p.get("created")).getFormat()).isEqualTo("date-time");
        assertThat(((Schema<?>) p.get("updated")).getFormat()).isEqualTo("date-time");
        assertThat(((Schema<?>) p.get("localDateTime")).getFormat()).isEqualTo("date-time");
        assertThat(((Schema<?>) p.get("oldDate")).getFormat()).isEqualTo("date-time");

        assertThat(((Schema<?>) p.get("due")).getFormat()).isEqualTo("date");
        assertThat(((Schema<?>) p.get("startTime")).getFormat()).isEqualTo("time");

        assertThat(((Schema<?>) p.get("id")).getType()).isEqualTo("string");
        assertThat(((Schema<?>) p.get("id")).getFormat()).isEqualTo("uuid");

        assertThat(((Schema<?>) p.get("homepage")).getFormat()).isEqualTo("uri");
        assertThat(((Schema<?>) p.get("fallback")).getFormat()).isEqualTo("uri");

        // BigDecimal / BigInteger are type:number with no format.
        assertThat(((Schema<?>) p.get("amount")).getType()).isEqualTo("number");
        assertThat(((Schema<?>) p.get("amount")).getFormat()).isNull();
        assertThat(((Schema<?>) p.get("huge")).getType()).isEqualTo("number");

        // Duration is type:string with no OpenAPI standard format.
        assertThat(((Schema<?>) p.get("timeout")).getType()).isEqualTo("string");
        assertThat(((Schema<?>) p.get("timeout")).getFormat()).isNull();
    }

    @Test
    public void stdlibOnlyBeanStillRegistersAsComponent() {
        // A bean composed entirely of stdlib types should still be a $ref'd component.
        var routes = List.of(route("POST", "/stdlib", "OpenApiTestController.postStdlib"));
        OpenAPI spec = generator.generate(routes);

        assertThat(requestBodySchema(spec, "/stdlib").get$ref())
                .isEqualTo("#/components/schemas/StdlibBean");
    }

    @Test
    public void optionalUnwrapsInnerTypeAndMarksNullable() {
        var routes = List.of(route("POST", "/opt", "OpenApiTestController.postOptional"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("OptionalBean");
        Map<String, Schema> p = bean.getProperties();

        // Optional<String> → {type: string, nullable: true}
        assertThat(((Schema<?>) p.get("nickname")).getType()).isEqualTo("string");
        assertThat(((Schema<?>) p.get("nickname")).getNullable()).isTrue();

        // Optional<Agent> → $ref with nullable sibling
        assertThat(((Schema<?>) p.get("agent")).get$ref()).isEqualTo("#/components/schemas/Agent");
        assertThat(((Schema<?>) p.get("agent")).getNullable()).isTrue();

        // Optional<List<String>> → array of string, nullable
        Schema<?> tags = (Schema<?>) p.get("tags");
        assertThat(tags).isInstanceOf(ArraySchema.class);
        assertThat(((Schema<?>) ((ArraySchema) tags).getItems()).getType()).isEqualTo("string");
        assertThat(tags.getNullable()).isTrue();
    }

    @Test
    public void rawOptionalDegradesGracefully() {
        var routes = List.of(route("POST", "/opt", "OpenApiTestController.postOptional"));
        OpenAPI spec = generator.generate(routes);

        // Raw Optional has no type parameter; schemaFor hits the unparameterized
        // classSchema path, isStdLib short-circuits, returns plain ObjectSchema.
        Schema<?> rawOpt = (Schema<?>) spec.getComponents().getSchemas()
                .get("OptionalBean").getProperties().get("rawOpt");
        assertThat(rawOpt).isNotNull();
        assertThat(rawOpt.get$ref()).isNull();
        assertThat(rawOpt.getNullable()).isNull();
    }

    @Test
    public void mapValueTypeIsPreservedViaAdditionalProperties() {
        var routes = List.of(route("POST", "/map", "OpenApiTestController.postMap"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("MapBean");
        Map<String, Schema> p = bean.getProperties();

        // Map<String, Long> → additionalProperties: {type: integer}
        Schema<?> counters = (Schema<?>) p.get("counters");
        assertThat(counters.getAdditionalProperties()).isInstanceOf(Schema.class);
        assertThat(((Schema<?>) counters.getAdditionalProperties()).getType()).isEqualTo("integer");

        // Map<String, Agent> → additionalProperties: {$ref: '#/components/schemas/Agent'}
        Schema<?> byKey = (Schema<?>) p.get("byKey");
        Schema<?> byKeyAdd = (Schema<?>) byKey.getAdditionalProperties();
        assertThat(byKeyAdd.get$ref()).isEqualTo("#/components/schemas/Agent");

        // Map<String, List<Agent>> → additionalProperties: ArraySchema(items: $ref)
        Schema<?> grouped = (Schema<?>) p.get("grouped");
        Schema<?> groupedAdd = (Schema<?>) grouped.getAdditionalProperties();
        assertThat(groupedAdd).isInstanceOf(ArraySchema.class);
        assertThat(((Schema<?>) ((ArraySchema) groupedAdd).getItems()).get$ref())
                .isEqualTo("#/components/schemas/Agent");
    }

    @Test
    public void rawMapDegradesGracefully() {
        var routes = List.of(route("POST", "/map", "OpenApiTestController.postMap"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> rawMap = (Schema<?>) spec.getComponents().getSchemas()
                .get("MapBean").getProperties().get("rawMap");
        // Raw Map → plain object, no additionalProperties.
        assertThat(rawMap.getAdditionalProperties()).isNull();
    }

    // -- PF-102 opaque JSON tree types --------------------------------------

    @Test
    public void gsonJsonElementFieldStaysOpaque() {
        var routes = List.of(route("POST", "/gson", "OpenApiTestController.postWithGsonTree"));
        OpenAPI spec = generator.generate(routes);

        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        // No JsonElement/JsonObject/JsonArray/JsonPrimitive/JsonNull schemas leak in.
        assertThat(schemas).doesNotContainKeys(
                "JsonElement", "JsonObject", "JsonArray", "JsonPrimitive", "JsonNull");

        Schema<?> bean = schemas.get("WithGsonTree");
        Schema<?> configProp = (Schema<?>) bean.getProperties().get("config");
        // The JsonElement field renders as a plain object schema (no $ref, no properties).
        assertThat(configProp.get$ref()).isNull();
        assertThat(configProp.getProperties()).isNullOrEmpty();
        assertThat(configProp.getType()).isEqualTo("object");
    }

    @Test
    public void mapValueOfGsonJsonElementStaysOpaque() {
        var routes = List.of(route("POST", "/gson-map", "OpenApiTestController.postWithMapOfGsonTree"));
        OpenAPI spec = generator.generate(routes);

        Schema<?> bean = spec.getComponents().getSchemas().get("WithMapOfGsonTree");
        Schema<?> attrs = (Schema<?>) bean.getProperties().get("attrs");
        // additionalProperties is set (Map handling kicked in) and the value is opaque.
        Schema<?> addProps = (Schema<?>) attrs.getAdditionalProperties();
        assertThat(addProps).isNotNull();
        assertThat(addProps.get$ref()).isNull();
        assertThat(addProps.getProperties()).isNullOrEmpty();
    }

    @Test
    public void jacksonJsonNodeFieldStaysOpaque() {
        var routes = List.of(route("POST", "/jn", "OpenApiTestController.postWithJacksonTreeBase"));
        OpenAPI spec = generator.generate(routes);

        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        assertThat(schemas).doesNotContainKey("JsonNode");

        Schema<?> bean = schemas.get("WithJacksonTreeBase");
        Schema<?> payload = (Schema<?>) bean.getProperties().get("payload");
        assertThat(payload.get$ref()).isNull();
        assertThat(payload.getProperties()).isNullOrEmpty();
    }

    @Test
    public void jacksonJsonNodeSubclassDetectedViaSuperclassWalk() {
        // ObjectNode extends ContainerNode<...> extends JsonNode. The check must
        // walk the superclass chain to find JsonNode and treat the subclass opaque.
        var routes = List.of(route("POST", "/on", "OpenApiTestController.postWithJacksonTreeSubclass"));
        OpenAPI spec = generator.generate(routes);

        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        assertThat(schemas).doesNotContainKeys("ObjectNode", "ContainerNode", "JsonNode");

        Schema<?> bean = schemas.get("WithJacksonTreeSubclass");
        Schema<?> payload = (Schema<?>) bean.getProperties().get("payload");
        assertThat(payload.get$ref()).isNull();
        assertThat(payload.getProperties()).isNullOrEmpty();
    }
}
