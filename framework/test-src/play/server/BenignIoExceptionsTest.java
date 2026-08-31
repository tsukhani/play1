package play.server;

import java.io.IOException;
import java.net.SocketException;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-172: the reset/broken-pipe match used to live as a private copy inside each of the
 * three pipeline suppressors (PF-109, PF-110, PF-172). Now that it is one shared
 * predicate, this is where its contract is stated — the suppressor tests exercise handler
 * behaviour, not the boundaries of the match.
 */
class BenignIoExceptionsTest {

    @Test
    void matchesTheExactMessagesTheJdkAndNettyProduce() {
        // What sun.nio.ch.SocketChannelImpl.throwConnectionReset actually throws, and the
        // write-side counterpart. These two literals are the entire suppression surface.
        assertTrue(BenignIoExceptions.isBenignReset(new SocketException("Connection reset")));
        assertTrue(BenignIoExceptions.isBenignReset(new IOException("Broken pipe")));
    }

    @Test
    void matchesOnSubstringSoPeerSuffixesStillCount() {
        // "Connection reset by peer" is the same event with a longer message; contains()
        // rather than equals() is what makes both spellings one case.
        assertTrue(BenignIoExceptions.isBenignReset(new SocketException("Connection reset by peer")));
        assertTrue(BenignIoExceptions.isBenignReset(new IOException("Broken pipe (write failed)")));
    }

    @Test
    void doesNotMatchDiagnosticMessagesOnTheSameExceptionType() {
        // The whole reason the match is on message text and not on type: SocketException
        // also carries faults an operator must see.
        assertFalse(BenignIoExceptions.isBenignReset(new SocketException("Network is unreachable")));
        assertFalse(BenignIoExceptions.isBenignReset(new SocketException("Too many open files")));
    }

    @Test
    void doesNotMatchNullMessage() {
        assertFalse(BenignIoExceptions.isBenignReset(new SocketException()));
    }

    @Test
    void doesNotMatchNonIoExceptions() {
        // A bug in application code that happens to mention a reset is still a bug.
        assertFalse(BenignIoExceptions.isBenignReset(new RuntimeException("Connection reset")));
    }

    @Test
    void matchesSslExceptionBecauseFilteringItIsTheCallersJob() {
        // SSLException IS an IOException and genuinely does carry "Connection reset by peer".
        // This predicate says nothing about whether it should be silenced; both SSL
        // suppressors test for SSLException BEFORE calling here, precisely because this
        // returns true. Changing that here would silently un-suppress the TLS pipelines.
        assertTrue(BenignIoExceptions.isBenignReset(new SSLException("Connection reset by peer")));
    }
}
