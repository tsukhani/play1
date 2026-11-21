package play.mvc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-237: a route whose path is plain text compares a request's path with it instead of running
 * a matcher, and a route without a host does not run its {@code .*} host pattern. Each route must
 * match exactly the requests the matchers matched, with the same arguments.
 */
public class RouteMatchingTest {

    private static final String[] ROUTES = {
            "/plaintext", "/", "/a/b-c_d~e%20", "/robots.txt", "/users/{id}", "/x/{<[0-9]+>id}", "/files/{name}.txt",
            "{client}.example.com/home", "example.com/home", "/UPPER/Case",
    };

    private static final String[] PATHS = {
            "/plaintext", "/plaintext/", "/Plaintext", "/plaintex", "/plaintextX", "/", "", "/a/b-c_d~e%20",
            "/a/b-c_d~e%2", "/robots.txt", "/robotsXtxt", "/users/7", "/users/", "/x/12", "/x/ab", "/files/a.txt",
            "/home", "/UPPER/Case", "/upper/case",
    };

    private static final String[] DOMAINS = { null, "", "example.com", "shop.example.com", "a\rb", "a\nb", "a\u0085b" };

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
    }

    @Test
    public void plainTextPathsAreRecognised() {
        assertThat(Router.Route.isPlainText("/plaintext")).isTrue();
        assertThat(Router.Route.isPlainText("/a/b-c_d~e%20")).isTrue();
        assertThat(Router.Route.isPlainText("/robots.txt")).isFalse();
        assertThat(Router.Route.isPlainText("/users/({id}[^/]+)")).isFalse();
        assertThat(route("GET", "/plaintext").literalPath).isEqualTo("/plaintext");
        assertThat(route("GET", "/users/{id}").literalPath).isNull();
        assertThat(route("GET", "/plaintext").anyHost).isTrue();
        assertThat(route("GET", "example.com/home").anyHost).isFalse();
    }

    @Test
    public void everyRouteMatchesWhatItsMatchersMatched() {
        for (String method : new String[] { "GET", "POST", "*" }) {
            for (String path : ROUTES) {
                Router.Route fast = route(method, path);
                Router.Route matchers = route(method, path);
                matchers.literalPath = null;
                matchers.anyHost = false;
                for (String requestMethod : new String[] { "GET", "HEAD", "POST", null }) {
                    for (String requestPath : PATHS) {
                        for (String domain : DOMAINS) {
                            assertThat(outcome(fast, requestMethod, requestPath, domain))
                                    .as("%s %s for %s %s on %s", method, path, requestMethod, requestPath, domain)
                                    .isEqualTo(outcome(matchers, requestMethod, requestPath, domain));
                        }
                    }
                }
            }
        }
    }

    private static Router.Route route(String method, String path) {
        return Router.getRoute(method, path, "Application.index", null, null);
    }

    private static String outcome(Router.Route route, String method, String path, String domain) {
        try {
            return Objects.toString(route.matches(method, path, null, domain));
        } catch (RuntimeException e) {
            return e.getClass().getName();
        }
    }
}
