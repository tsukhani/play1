package controllers;

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
}
