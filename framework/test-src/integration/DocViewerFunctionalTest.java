package integration;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static play.test.FunctionalTest.*;

/**
 * PF-164: covers the docviewer module's {@code /@docs} routing. The module is mounted into the
 * integration fixture by {@code testapp/modules/docviewer}, a marker file pointing at
 * {@code ../modules/docviewer}.
 *
 * <p>This suite exists because PF-163 — {@code /@docs} redirecting to itself forever for any
 * request without an {@code accept-language} header — shipped undetected. docviewer's
 * {@code app/} is compiled at runtime by {@code ApplicationClassloader}, so its controllers are
 * unreachable from any JUnit sourceset and the module had no tests at all. Crucially the loop
 * lived in the *interaction* between three things — the ControllersEnhancer turning a
 * cross-action call into a redirect, reverse routing choosing a route for the args it is given,
 * and {@code prependRoute} making later registrations win — so no unit test could have
 * reproduced it. Only a booted application exercises all three, which is why this is an
 * integration test rather than a cheaper one.
 */
@ExtendWith(IntegrationTestExtension.class)
public class DocViewerFunctionalTest {

    /** Hops allowed before a redirect chain is considered non-terminating. */
    private static final int MAX_HOPS = 5;

    @Test
    public void docviewerModuleIsMounted() {
        assertTrue(play.Play.modules.containsKey("docviewer"),
                "docviewer should be mounted in the integration fixture; without it every "
                        + "assertion below would vacuously pass on a 404");
    }

    /**
     * PF-163 regression. Fails against the pre-fix controller, which reverse-routed an empty
     * docLang to the single-segment /@docs/home — a path onRoutesLoaded maps back to index().
     */
    @Test
    public void docsIndexTerminatesWithoutAcceptLanguageHeader() {
        Response response = followRedirects("/@docs", null);
        assertIsOk(response);
        assertContentMatch("Play manual", response);
    }

    @Test
    public void docsIndexTerminatesWithAcceptLanguageHeader() {
        Response response = followRedirects("/@docs", "en-US,en;q=0.9");
        assertIsOk(response);
        assertContentMatch("Play manual", response);
    }

    /**
     * The specific path that looped: onRoutesLoaded registers /@docs/home -> index after
     * /@docs/{id} -> page, and prependRoute makes the later registration match first.
     */
    @Test
    public void docsHomeTerminates() {
        assertIsOk(followRedirects("/@docs/home", null));
    }

    /**
     * Guards the fix's shape, not just its effect. The obvious alternative fix — deleting the
     * shadowing /@docs/home route so the path falls through to page() — also stops the loop, but
     * silently drops accept-language auto-detection for that URL. Asserting the redirect still
     * carries the negotiated language keeps that regression visible.
     */
    @Test
    public void acceptLanguageIsCarriedIntoTheRedirect() {
        Response response = GET(requestWith("fr-FR,fr;q=0.9"), "/@docs");
        assertStatus(302, response);
        String location = response.getHeader("Location");
        assertTrue(location != null && location.contains("/fr/"),
                "redirect should carry the negotiated language, got: " + location);
    }

    /** With no header to negotiate from, the redirect must still carry a language segment. */
    @Test
    public void missingAcceptLanguageDefaultsToEnglish() {
        Response response = GET(requestWith(null), "/@docs");
        assertStatus(302, response);
        String location = response.getHeader("Location");
        assertTrue(location != null && location.contains("/en/"),
                "redirect should default to /en/, got: " + location);
    }

    /**
     * Route-precedence coverage: no parameterless /@docs route may resolve to an action that
     * redirects back to that same path. Enumerated rather than derived from Router.routes
     * because the remaining /@docs routes take path parameters that cannot be synthesised.
     */
    @Test
    public void noParameterlessDocsRouteRedirectsToItself() {
        for (String path : new String[] { "/@docs", "/@docs/", "/@docs/home" }) {
            Response response = followRedirects(path, null);
            assertTrue(response.status == 200,
                    path + " did not terminate in a 200 (status " + response.status + ")");
        }
    }

    /**
     * Walks a redirect chain to its first non-302 response, failing rather than hanging if the
     * chain does not terminate. FunctionalTest.GET(url, true) follows exactly one hop and drops
     * the query string, neither of which suits an assertion about termination.
     */
    private static Response followRedirects(String url, String acceptLanguage) {
        String current = url;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            Response response = GET(requestWith(acceptLanguage), current);
            if (Http.StatusCode.FOUND != response.status) {
                return response;
            }
            String location = response.getHeader("Location");
            assertNotNull(location, "302 from " + current + " carried no Location header");
            current = toRelative(location);
        }
        return fail("redirect chain from " + url + " did not terminate within " + MAX_HOPS
                + " hops (last: " + current + ") — /@docs is looping again, see PF-163");
    }

    /** Play sends absolute Location values; GET() wants an app-relative url. */
    private static String toRelative(String location) {
        if (!location.startsWith("http")) {
            return location;
        }
        URI uri = URI.create(location);
        return uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
    }

    private static Request requestWith(String acceptLanguage) {
        Request request = newRequest();
        if (acceptLanguage != null) {
            request.headers.put("accept-language", new Http.Header("accept-language", acceptLanguage));
        }
        return request;
    }
}
