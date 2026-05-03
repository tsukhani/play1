package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import play.mvc.Controller;

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
}
