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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-177 end-to-end: work an action hands to another thread logs under the request that handed
 * it off. Driven through the real Netty server against {@code ContextController}, because the
 * thing being carried only exists there: {@code ActionInvoker.invoke} puts the request's keys
 * into Log4j's {@code ThreadContext} on the request's virtual thread and clears them in its
 * finally block, before {@code JobsPlugin.afterInvocation} submits the after-request jobs.
 *
 * <p>Every assertion compares against the context the action itself reported, not against
 * expected literals: {@code request_id} is a fresh UUID per request, and that is also what makes
 * "the job ran under <em>this</em> request" a sharp claim rather than "under some request".</p>
 */
public class ContextPropagationFunctionalTest {

    // The cleartext listener: nothing here depends on TLS. HTTP/1.1 is pinned because the
    // server does not speak h2c, and the JDK client would otherwise try to upgrade to it.
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
    void wrappedTaskSeesTheRequestsLoggingContextAndTheWorkerGetsItsOwnBack() throws Exception {
        JsonObject seen = get("/context/wrapped");
        JsonObject request = seen.getAsJsonObject("request");

        // Guard for everything below: the request does have the context ActionInvoker gives it.
        assertFalse(request.get("request_id").getAsString().isBlank(), "no request_id in: " + request);
        assertEquals("GET", request.get("http_method").getAsString());
        assertEquals("/context/wrapped", request.get("http_path").getAsString());
        assertEquals("ContextController.wrapped", request.get("action_name").getAsString());

        // The control: handed to the same thread without wrap(), the task sees none of it.
        assertEquals(workersOwn(), seen.getAsJsonObject("unwrapped"),
                "an unwrapped task should see only the worker thread's own context");
        // Wrapped, it sees the request's whole map, and not the worker's own key mixed in.
        assertEquals(request, seen.getAsJsonObject("wrapped"));
        // And the worker has its own context back afterwards, not an empty one.
        assertEquals(workersOwn(), seen.getAsJsonObject("workerAfter"),
                "the worker thread should have its own context back after the wrapped task");
    }

    @Test
    void nowJobSeesTheSubmittingRequestsLoggingContext() throws Exception {
        assertJobRanUnderTheRequestThatStartedIt("now");
    }

    @Test
    void delayedJobSeesTheSubmittingRequestsLoggingContext() throws Exception {
        assertJobRanUnderTheRequestThatStartedIt("in");
    }

    /**
     * The case that capturing at submit time gets wrong: an after-request job is submitted by
     * {@code JobsPlugin.afterInvocation}, when the request's context is already gone.
     */
    @Test
    void afterRequestJobSeesTheSubmittingRequestsLoggingContext() throws Exception {
        assertJobRanUnderTheRequestThatStartedIt("afterRequest");
    }

    @Test
    void jobStartedFromARequestGetsItsOwnEntityManager() throws Exception {
        JsonObject seen = get("/context/entityManager");

        assertTrue(seen.get("ownEntityManager").getAsBoolean(),
                "the job must not be handed the request's EntityManager: " + seen);
        // The job's transaction closed the EntityManager it used when it ended. Had that been
        // the request's, the request would be left holding a closed one.
        assertFalse(seen.get("jobEntityManagerOpen").getAsBoolean(), "the job's EntityManager outlived the job: " + seen);
        assertTrue(seen.get("requestEntityManagerOpen").getAsBoolean(),
                "the request's EntityManager was closed by the job it started: " + seen);
        assertEquals(0, seen.get("count").getAsInt(), "the request could not query after the job ran: " + seen);
    }

    private static void assertJobRanUnderTheRequestThatStartedIt(String how) throws Exception {
        JsonObject started = get("/context/job?how=" + how);
        JsonObject request = started.getAsJsonObject("request");
        assertEquals("/context/job", request.get("http_path").getAsString());

        JsonObject result = get("/context/jobResult?token=" + started.get("token").getAsString());
        JsonObject job = result.getAsJsonObject("context");

        assertTrue(job.has("jobId"), "a " + how + " job ran without a jobId: " + job);
        assertFalse(job.remove("jobId").getAsString().isBlank(), "a " + how + " job ran with an empty jobId");
        // Apart from the jobId, exactly the map the action that started it had.
        assertEquals(request, job, "a " + how + " job did not run under the request that started it");
        // Which is not the request that fetched the result, the only other one around.
        assertNotEquals(result.getAsJsonObject("resultRequest").get("request_id"), job.get("request_id"));

        // The request-scoped objects stay behind.
        assertFalse(result.get("sawRequest").getAsBoolean(), "the job saw the request: " + result);
        assertFalse(result.get("sawSession").getAsBoolean(), "the job saw the session: " + result);
        assertFalse(result.get("sawParams").getAsBoolean(), "the job saw the params: " + result);
    }

    private static JsonObject workersOwn() {
        JsonObject context = new JsonObject();
        context.addProperty("worker", "own");
        return context;
    }

    private static JsonObject get(String path) throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " returned " + response.statusCode() + ": " + response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
