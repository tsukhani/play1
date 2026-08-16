package play.mvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import play.Play;
import play.mvc.Http.Request;
import play.mvc.results.NotFound;
import play.mvc.results.RenderStatic;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RouterTest {

    @AfterEach
    void tearDown() {
        Router.routes.clear();
    }

    @Test
    public void test_getBaseUrl() {

        Play.configuration = new Properties();

        // test with currentRequest
        Http.Request request = Http.Request.createRequest(new Http.Request.RequestData(
                null,
                "GET",
                "/",
                "",
                null,
                null,
                null,
                null,
                false,
                80,
                "localhost",
                false,
                null,
                null
        ));

        Http.Request.current.set( request );
        assertThat(Router.getBaseUrl()).isEqualTo("http://localhost");

        // test without current request
        Http.Request.current.remove();
        // application.baseUrl without trailing /
        Play.configuration.setProperty("application.baseUrl", "http://a");
        assertThat(Router.getBaseUrl()).isEqualTo("http://a");

        // application.baseUrl with trailing /
        Play.configuration.setProperty("application.baseUrl", "http://b/");
        assertThat(Router.getBaseUrl()).isEqualTo("http://b");
    }

    @Test
    public void test_hostStaticDir() {
        
        Play.configuration = new Properties();
        
        // we add a static route for a specific domain only
        Router.addRoute("GET", "example.com/pics/", "staticDir:/public/images");
        // another static route with NO specific domain
        Router.addRoute("GET", "/music/", "staticDir:/public/mp3");

        // we request a static image file (which lives only on a specific domain)
        Http.Request imageRequest = Http.Request.createRequest(new Http.Request.RequestData(
                null,
                "GET",
                "/pics/chuck-norris.jpg",
                "",
                null,
                null,
                null,
                null,
                false,
                80,
                "localhost", // domain gets changed below a few times
                false,
                null,
                null
        ));
        // we also request a static music file (which lives on NO specific domain)
        Http.Request musicRequest = Http.Request.createRequest(new Http.Request.RequestData(
                null,
                "GET",
                "/music/michael-jackson_black-or-white.mp3",
                "",
                null,
                null,
                null,
                null,
                false,
                80,
                "localhost", // domain gets changed below a few times
                false,
                null,
                null
        ));
        
        // Test on localhost
        assertFalse(canRenderFile(imageRequest), "Image file [" + imageRequest.domain + "] from the wrong/different domain must not be found");
        assertTrue(canRenderFile(musicRequest), "Image file [" + imageRequest.domain + "] from the wrong/different domain must not be found");
        
        // Test on localhost:9000
        imageRequest.port = 9000;
        musicRequest.port = 9000;
        assertFalse(canRenderFile(imageRequest), "Image file [" + imageRequest.domain + "] from the wrong/different domain must not be found");
        assertTrue(canRenderFile(musicRequest), "Image file [" + imageRequest.domain + "] from the wrong/different domain must not be found");
        
        // we request the image file from a "wrong"/different domain, it will not be found
        imageRequest.port = 80;
        musicRequest.port = 80;
        imageRequest.domain = "google.com";
        assertFalse(canRenderFile(imageRequest),"Image file [" + imageRequest.domain + "] from the wrong/different domain must not be found");

        // same for musicfile, but it will be rendered because the domain doesn't matter
        musicRequest.domain = "google.com";
        
        assertTrue(canRenderFile(musicRequest), "Musicfile [" + musicRequest.domain + "] file  must be found");
                
        // we request the image file from the "right" domain
        imageRequest.domain = "example.com";
        assertTrue(canRenderFile(imageRequest), "Image file [" + musicRequest.domain + "] from the right domain must be found");
        
        // same for musicfile, it will be rendered again also on this domain
        musicRequest.domain = "example.com";
        assertTrue(canRenderFile(musicRequest), "Musicfile [" + musicRequest.domain + "] from the right domain must be found");
    }
    
    /**
     * PF-170: the {@code x-http-method-override} query-string parameter must not rewrite the request
     * method unless the target method is listed in {@code http.allowed.method.override} AND the wire
     * method is something other than GET. A cross-site top-level GET navigation is the shape
     * {@code SameSite=Lax} still attaches cookies to, so elevating a GET is the CSRF vector; the POST
     * cases are the shape {@code #{form}}/{@code #{a}} emit for PUT/DELETE/PATCH actions.
     */
    @Test
    public void test_queryStringMethodOverride_isGatedAndNeverElevatesGet() {
        Play.configuration = new Properties();
        Router.addRoute("GET", "/widget", "Widgets.show");
        Router.addRoute("POST", "/widget", "Widgets.create");
        Router.addRoute("PUT", "/widget", "Widgets.update");

        // No opt-in: the override is inert on both the attack shape and the #{form} shape.
        assertThat(routeAction("GET", "x-http-method-override=PUT")).isEqualTo("Widgets.show");
        assertThat(routeAction("POST", "x-http-method-override=PUT")).isEqualTo("Widgets.create");

        Play.configuration.setProperty("http.allowed.method.override", "PUT");

        // Opted in: #{form}'s POST-with-override reaches the PUT route...
        assertThat(routeAction("POST", "x-http-method-override=PUT")).isEqualTo("Widgets.update");
        // ...but the opt-in never re-opens GET elevation.
        assertThat(routeAction("GET", "x-http-method-override=PUT")).isEqualTo("Widgets.show");

        // A method that is not opted in stays inert even from a POST.
        assertThat(routeAction("POST", "x-http-method-override=GET")).isEqualTo("Widgets.create");

        // Opting POST in does not make GET->POST work, unlike the header form in PlayHandler.
        Play.configuration.setProperty("http.allowed.method.override", "POST");
        assertThat(routeAction("GET", "x-http-method-override=POST")).isEqualTo("Widgets.show");

        // The parameter is still honoured alongside others in the query string.
        Play.configuration.setProperty("http.allowed.method.override", "PUT");
        assertThat(routeAction("POST", "page=2&x-http-method-override=PUT&sort=name")).isEqualTo("Widgets.update");

        // Entries are trimmed, so a list written with spaces opts in rather than failing closed.
        Play.configuration.setProperty("http.allowed.method.override", "POST, PUT, DELETE");
        assertThat(routeAction("POST", "x-http-method-override=PUT")).isEqualTo("Widgets.update");
    }

    /** Route {@code /widget} with the given wire method and query string, returning the resolved action. */
    private static String routeAction(String method, String querystring) {
        Request request = Http.Request.createRequest(new Http.Request.RequestData(
                null,
                method,
                "/widget",
                querystring,
                null,
                null,
                null,
                null,
                false,
                80,
                "localhost",
                false,
                null,
                null
        ));
        Router.route(request);
        return request.action;
    }

    public boolean canRenderFile(Request request){
        try {
            Router.route(request);
        } catch(RenderStatic rs) {
            return true;
        }  catch(NotFound nf) {
            return false;
        }
        return false;
    }
}
