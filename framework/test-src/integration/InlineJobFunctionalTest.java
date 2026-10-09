package integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job run with {@code call()} or {@code run()} from inside an action is on the request's own
 * thread. It used to open a second invocation there, and that invocation's teardown took the
 * request's with it: {@code JobsPlugin} dropped the after-request queue, so the request ended
 * with a NullPointerException and a 500, and the database and validation state of the request
 * went the same way. Only a booted application with JPA shows all of it, hence the real server.
 */
public class InlineJobFunctionalTest {

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
    void jobCalledInlineLeavesTheRequestWhole() throws Exception {
        assertRequestSurvivesAnInlineJob("call");
    }

    @Test
    void jobRunInlineLeavesTheRequestWhole() throws Exception {
        assertRequestSurvivesAnInlineJob("run");
    }

    @Test
    void failureOfAnInlineJobReachesTheActionAndLeavesTheRequestWhole() throws Exception {
        JsonObject seen = json(get("/inline/failing"));

        assertEquals("inline job failed", seen.get("thrown").getAsString());
        assertTrue(seen.get("requestEntityManagerOpen").getAsBoolean(), "the request's EntityManager was closed: " + seen);
        assertTrue(seen.get("count").getAsLong() >= 0);
    }

    private static void assertRequestSurvivesAnInlineJob(String how) throws Exception {
        JsonObject seen = json(get("/inline/run?how=" + how));

        // The job ran, in the request's own transaction.
        assertEquals(seen.get("count").getAsLong(), seen.get("jobCount").getAsLong());
        // And the request still has what it had before the job.
        assertTrue(seen.get("requestEntityManagerOpen").getAsBoolean(), "the request's EntityManager was closed: " + seen);
        assertTrue(seen.get("sameEntityManager").getAsBoolean(), "the request was given another EntityManager: " + seen);
        assertTrue(seen.get("validationKept").getAsBoolean(), "the request's validation errors were dropped: " + seen);
        assertTrue(seen.get("invocationContext").getAsBoolean(), "the request's invocation context was removed: " + seen);

        // A job queued with afterRequest() after the inline one is still started.
        String token = seen.get("token").getAsString();
        assertEquals(token, get("/inline/after?token=" + token).body());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " answered: " + response.body());
        return response;
    }
}
