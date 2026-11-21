package integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import play.mvc.Scope;
import play.utils.Utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-237: what a plain request through the real server is given, now that its path no longer
 * repeats work for it. Each test covers a cut where the work it removed showed:
 * <ul>
 * <li>the Date header and the deletions of the session, flash and errors cookies, formatted and
 * encoded once per second, and the response headers no longer copied: each header once, with the
 * value it had;</li>
 * </ul>
 */
public class RequestPathFunctionalTest {

    // The cleartext listener; HTTP/1.1 is pinned because the server does not speak h2c.
    private static final String BASE = "http://127.0.0.1:19080";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void aPlainResponseCarriesEachHeaderOnce() throws Exception {
        long before = System.currentTimeMillis();
        HttpResponse<byte[]> response = CLIENT.send(HttpRequest.newBuilder(URI.create(BASE + "/json")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        long after = System.currentTimeMillis();
        assertEquals(200, response.statusCode());

        List<String> date = response.headers().allValues("date");
        assertEquals(1, date.size(), () -> "date: " + date);
        long sent = Utils.parseHttpDate(date.get(0)).getTime();
        assertTrue(sent >= before / 1000 * 1000 && sent <= after, () -> "date " + date + " outside the request");

        assertEquals(List.of(String.valueOf(response.body().length)), response.headers().allValues("content-length"));
        for (String name : new String[] { "server", "content-type", "cache-control", "x-content-type-options",
                "x-frame-options", "referrer-policy", "x-xss-protection", "content-security-policy" }) {
            assertEquals(1, response.headers().allValues(name).size(), () -> name + ": " + response.headers().map());
        }

        List<String> cookies = response.headers().allValues("set-cookie");
        assertEquals(3, cookies.size(), () -> "set-cookie: " + cookies);
        for (String scope : new String[] { "_ERRORS", "_FLASH", "_SESSION" }) {
            Pattern deletion = Pattern.compile(Pattern.quote(Scope.COOKIE_PREFIX + scope)
                    + "=; Max-Age=0; Expires=[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT; Path=/"
                    + "(; Secure)?(; HTTPOnly)?; SameSite=Lax");
            assertEquals(1, cookies.stream().filter(c -> deletion.matcher(c).matches()).count(),
                    () -> scope + " deletion not sent once in " + cookies);
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(BASE + path)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
