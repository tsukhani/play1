package play.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import play.mvc.Http.Response;

/**
 * A functional-test invocation that outruns its latch is abandoned rather than cancelled, so it
 * keeps the single-threaded executor. TestEngine tracks it so the requests behind it fail
 * immediately naming it, instead of each timing out on its own.
 */
public class TestEngineAbandonedInvocationTest {

    @Test
    public void abandonedInvocationBlocksTheExecutorUntilItFinishes() {
        assertNull(TestEngine.blockedBy());

        CompletableFuture<Void> hung = new CompletableFuture<>();
        TestEngine.abandonInvocation("GET /hangs", hung);
        assertEquals("GET /hangs", TestEngine.blockedBy());

        hung.complete(null);
        assertNull(TestEngine.blockedBy(), "a finished invocation no longer holds the executor thread");
    }

    @Test
    public void requestsBehindAnAbandonedInvocationFailImmediatelyNamingIt() {
        CompletableFuture<Void> hung = new CompletableFuture<>();
        TestEngine.abandonInvocation("GET /hangs", hung);
        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> FunctionalTest.makeRequest(FunctionalTest.newRequest(), new Response()));
            assertTrue(thrown.getMessage().contains("GET /hangs"),
                    "the failure must name the request that never completed, not its victim: " + thrown.getMessage());
        } finally {
            hung.complete(null);
        }
    }
}
