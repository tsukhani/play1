package integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import play.mvc.Http.Response;

import static play.test.FunctionalTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full request lifecycle:
 * Router -> Controller -> Template -> Response
 *
 * Does NOT extend FunctionalTest (which would trigger PlayJUnitExtension
 * pointing at the wrong directory). Instead uses its static methods directly.
 */
@ExtendWith(IntegrationTestExtension.class)
public class RequestLifecycleTest {

    @Test
    public void playIsStarted() {
        assertTrue(play.Play.started, "Play should be started");
    }

    @Test
    public void indexReturnsRenderedTemplate() {
        Response response = GET("/");
        assertIsOk(response);
        assertContentMatch("Hello, World!", response);
    }

    @Test
    public void routeParameterBindingAndTemplateRendering() {
        Response response = GET("/hello/Alice");
        assertIsOk(response);
        assertContentMatch("Hello, Alice!", response);
    }

    @Test
    public void postWithFormDataEchoesContent() {
        Response response = POST("/echo", "application/x-www-form-urlencoded", "message=testing123");
        assertIsOk(response);
        assertContentEquals("echo:testing123", response);
    }

    @Test
    public void redirectReturns302() {
        Response response = GET("/redirect");
        assertStatus(302, response);
    }

    @Test
    public void jsonEndpointReturnsJsonContentType() {
        Response response = GET("/json");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertContentMatch("\"status\"\\s*:\\s*\"ok\"", response);
        assertContentMatch("\"framework\"\\s*:\\s*\"play\"", response);
    }

    @Test
    public void unknownRouteThrowsNotFound() {
        assertThrows(play.mvc.results.NotFound.class, () -> GET("/nonexistent-path"));
    }

    @Test
    public void staticFileServing() {
        Response response = GET("/public/test.txt");
        assertIsOk(response);
    }
}
