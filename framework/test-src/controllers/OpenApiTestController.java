package controllers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import play.mvc.Controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Fake controller used by {@code play.plugins.openapi.OpenApiGeneratorTest}.
 * Sits in the {@code controllers} package so the generator's "controllers."
 * prefix resolution finds it via reflection. Methods do not actually invoke
 * Play's response machinery — the test never calls them, only reflects on
 * their signatures.
 */
public class OpenApiTestController extends Controller {

    public static void ping() {
    }

    public static void show(Long id) {
    }

    public static void list() {
    }

    public static void create(String name) {
    }

    public static void update(String name) {
    }

    public static void delete() {
    }

    // -- PF-81 annotation-enrichment fixtures -------------------------------

    /** Bean used as a Schema implementation reference. */
    public static class User {
        public Long id;
        public String name;
    }

    @Operation(summary = "Fetch user")
    public static void annotatedSummary(Long id) {
    }

    @Operation(description = "Returns the canonical user record.")
    public static void annotatedDescription(Long id) {
    }

    @ApiResponse(responseCode = "404", description = "Not found")
    public static void annotatedNotFound(Long id) {
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "Not found")
    })
    public static void annotatedMultipleResponses(Long id) {
    }

    @RequestBody(content = @Content(schema = @Schema(implementation = User.class)))
    public static void annotatedRequestBody(User user) {
    }

    public static void annotatedParameter(
            @Parameter(name = "id", description = "user id") Long id) {
    }

    @Operation(tags = {"admin"})
    public static void annotatedMethodTag() {
    }

    // -- PF-97 bean-introspection fixtures ----------------------------------

    /**
     * Locally-declared {@code @Transient} so the test does not pull in JPA.
     * The generator detects this annotation by its simple name, mirroring how
     * it would handle {@code javax.persistence.Transient} in a real app.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Transient {}

    /** Simple bean with primitive fields. */
    public static class Agent {
        public Long id;
        public String name;
    }

    /** Self-referential bean — exercises cycle detection. */
    public static class Node {
        public String label;
        public Node parent;
    }

    /** Bean with no public fields — should degrade to an empty ObjectSchema. */
    public static class EmptyBean {
        private String hidden;
    }

    /** Bean polluted with JPA/ASM enhancement artefacts. */
    public static class JpaPolluted {
        public String real;
        public Object _persistence_listener_holder;
        public Object _persistence_primaryKey;
        public Object __synthetic;
        public transient String transientField;
        @Transient public String beansTransientField;
    }

    /** Bean with field-level @Schema enrichment. */
    public static class AnnotatedFields {
        @Schema(description = "the agent's display name") public String name;
        @Schema(example = "kimi-k2.6") public String model;
        @Schema(format = "email") public String contact;
        @Schema(format = "uri") public String homepage;
        @Schema(nullable = true) public String nickname;
        @Schema(deprecated = true) public String oldField;
        @Schema(minimum = "0", maximum = "100") public Integer score;
        @Schema(pattern = "^[A-Z]+$") public String code;
        @Schema(required = true) public String requiredViaRequired;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) public String requiredViaMode;
    }

    /** Bean exercising Jackson/Gson library-annotation interop. */
    public static class JsonAnnotated {
        @JsonIgnore public String hiddenByJackson;
        @Expose(serialize = false) public String hiddenByGson;
        @JsonProperty("created_at") public Long createdAt;
        @SerializedName("updated_at") public Long updatedAt;
        @JsonProperty(value = "kind", required = true) public String kind;
    }

    /** Top-level Foo for simple-name-collision test. */
    public static class Foo {
        public String a;
    }

    /** Second nested namespace with another Foo. */
    public static class Nested {
        public static class Foo {
            public String b;
        }
    }

    public static void getAgent(Long id) { _ignore(id); }
    public static void listAgents() {}
    public static void getNode() {}
    public static void getEmpty() {}
    public static void getJpaPolluted() {}
    public static void getAnnotated() {}
    public static void getJsonAnnotated() {}
    public static void getFoo() {}
    public static void getNestedFoo() {}

    // Parameter typing — return List<Agent>, take Node, etc. — is driven via the
    // method-signature reflection path, so these "_ignore" sinks exist purely
    // to suppress unused-parameter warnings without changing the signatures.
    @SuppressWarnings("unused")
    private static void _ignore(Object o) {}

    public static List<Agent> agentList() { return null; }
    public static void postAgent(Agent body) { _ignore(body); }
    public static void postNode(Node body) { _ignore(body); }
    public static void postFoo(Foo body) { _ignore(body); }
    public static void postNestedFoo(Nested.Foo body) { _ignore(body); }
    public static void postEmpty(EmptyBean body) { _ignore(body); }
    public static void postJpaPolluted(JpaPolluted body) { _ignore(body); }
    public static void postAnnotated(AnnotatedFields body) { _ignore(body); }
    public static void postJsonAnnotated(JsonAnnotated body) { _ignore(body); }
}
