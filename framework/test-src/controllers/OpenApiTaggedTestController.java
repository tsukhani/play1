package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import play.mvc.Controller;

/**
 * Fake controller for PF-81 — exercises the class-level {@code @Tag} cascade.
 * Kept separate from {@link OpenApiTestController} so the cascade doesn't
 * affect the unannotated PF-12 regression cases.
 */
@Tag(name = "users")
public class OpenApiTaggedTestController extends Controller {

    public static void plain() {
    }

    @Operation(tags = {"admin"})
    public static void taggedByMethod() {
    }
}
