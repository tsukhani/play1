package play.server;

import java.io.IOException;
import java.net.SocketException;

import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-110: verifies that the plain-HTTP exception suppressor consumes the small
 * set of benign RST/Broken-pipe IOExceptions that browsers routinely produce
 * when tearing down speculative TCP connections (six-per-host parallelism),
 * while leaving every other failure mode propagating to the tail.
 *
 * <p>Unlike PF-109's SSL counterpart, this handler does not self-remove — there
 * is no handshake-completion event on the plain pipeline, and steady-state RSTs
 * are exactly the case we want to keep silent. {@link #stays_in_pipeline_across_many_events}
 * locks that in.
 *
 * <p>{@link EmbeddedChannel}'s pipeline captures unhandled exceptions at its
 * tail — {@code checkException()} rethrows the last captured one — which
 * directly mirrors the "reached at the tail of the pipeline" path we are
 * suppressing in production. Consumed exceptions never reach the tail and
 * {@code checkException()} returns silently.
 */
class PlainHttpExceptionSuppressorTest {

    @Test
    void consumesConnectionResetAndClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new PlainHttpExceptionSuppressor());

        channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));

        channel.checkException();
        assertFalse(channel.isOpen(), "channel must be closed after RST suppression");
    }

    @Test
    void consumesBrokenPipeAndClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new PlainHttpExceptionSuppressor());

        channel.pipeline().fireExceptionCaught(new IOException("Broken pipe"));

        channel.checkException();
        assertFalse(channel.isOpen(), "channel must be closed after Broken pipe suppression");
    }

    @Test
    void propagatesUnrelatedSocketExceptionToTail() {
        EmbeddedChannel channel = new EmbeddedChannel(new PlainHttpExceptionSuppressor());
        SocketException unreachable = new SocketException("Network is unreachable");

        channel.pipeline().fireExceptionCaught(unreachable);

        SocketException thrown = assertThrows(SocketException.class, channel::checkException);
        assertSame(unreachable, thrown,
                "non-benign SocketException must reach the tail unchanged so operators can diagnose");
    }

    @Test
    void propagatesRuntimeExceptionToTail() {
        EmbeddedChannel channel = new EmbeddedChannel(new PlainHttpExceptionSuppressor());
        RuntimeException bug = new RuntimeException("unexpected");

        channel.pipeline().fireExceptionCaught(bug);

        RuntimeException thrown = assertThrows(RuntimeException.class, channel::checkException);
        assertSame(bug, thrown, "RuntimeException must propagate so bugs surface at the tail");
    }

    @Test
    void stays_in_pipeline_across_many_events() {
        // Unlike SslHandshakeExceptionSuppressor, this handler must NOT self-remove.
        // The plain pipeline has no handshake-completion event to hook on, and the
        // browser speculative-connect RSTs we want silenced happen continuously
        // throughout the channel's life (every page load), not just at startup.
        PlainHttpExceptionSuppressor suppressor = new PlainHttpExceptionSuppressor();
        EmbeddedChannel channel = new EmbeddedChannel(suppressor);
        assertNotNull(channel.pipeline().context(suppressor),
                "precondition: suppressor is in the pipeline");

        // A handful of unrelated user events — none should trip a removal path.
        channel.pipeline().fireUserEventTriggered("noop-1");
        channel.pipeline().fireUserEventTriggered("noop-2");
        channel.pipeline().fireUserEventTriggered("noop-3");

        assertTrue(channel.isOpen(), "channel must stay open across benign user events");
        assertNotNull(channel.pipeline().get(PlainHttpExceptionSuppressor.class),
                "suppressor must remain in the pipeline — no self-removal on the plain-HTTP leg");
    }
}
