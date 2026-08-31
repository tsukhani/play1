package play.server.ssl;

import java.io.IOException;
import java.net.SocketException;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PF-172: verifies that the steady-state TLS suppressor consumes the benign
 * RST/Broken-pipe IOExceptions browsers produce when tearing down speculative
 * connections (six-per-host parallelism, h3-vs-h2 races) <em>after</em> the TLS
 * handshake has completed, while leaving every other failure mode propagating.
 *
 * <p>The decisive difference from {@link SslHandshakeExceptionSuppressorTest} is
 * {@link #staysInPipelineAfterHandshakeCompletion}: PF-109's handshake suppressor
 * removes itself on a successful {@link SslHandshakeCompletionEvent} — correct for
 * its purpose, but that removal is exactly what left the steady state uncovered.
 * This handler must survive the same event.
 *
 * <p>{@link EmbeddedChannel}'s pipeline captures unhandled exceptions at its tail —
 * {@code checkException()} rethrows the last captured one — which directly mirrors
 * the "reached at the tail of the pipeline" path being suppressed in production.
 */
class SslSteadyStateExceptionSuppressorTest {

    @Test
    void consumesConnectionResetAndClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslSteadyStateExceptionSuppressor());

        channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));

        channel.checkException();
        assertFalse(channel.isOpen(), "channel must be closed after RST suppression");
    }

    @Test
    void consumesBrokenPipeAndClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslSteadyStateExceptionSuppressor());

        channel.pipeline().fireExceptionCaught(new IOException("Broken pipe"));

        channel.checkException();
        assertFalse(channel.isOpen(), "channel must be closed after Broken pipe suppression");
    }

    @Test
    void propagatesSslExceptionEvenWhenItsMessageNamesAReset() {
        // SSLException IS an IOException, so the message check alone would swallow a
        // genuine TLS fault reported as "Connection reset by peer". The SSLException
        // branch is checked first precisely to keep those visible.
        EmbeddedChannel channel = new EmbeddedChannel(new SslSteadyStateExceptionSuppressor());
        SSLException tlsFault = new SSLException("Connection reset by peer");

        channel.pipeline().fireExceptionCaught(tlsFault);

        SSLException thrown = assertThrows(SSLException.class, channel::checkException);
        assertSame(tlsFault, thrown, "genuine TLS faults must still reach the tail");
    }

    @Test
    void propagatesUnrelatedSocketExceptionToTail() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslSteadyStateExceptionSuppressor());
        SocketException unreachable = new SocketException("Network is unreachable");

        channel.pipeline().fireExceptionCaught(unreachable);

        SocketException thrown = assertThrows(SocketException.class, channel::checkException);
        assertSame(unreachable, thrown,
                "non-benign SocketException must reach the tail unchanged so operators can diagnose");
    }

    @Test
    void propagatesRuntimeExceptionToTail() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslSteadyStateExceptionSuppressor());
        RuntimeException bug = new RuntimeException("unexpected");

        channel.pipeline().fireExceptionCaught(bug);

        RuntimeException thrown = assertThrows(RuntimeException.class, channel::checkException);
        assertSame(bug, thrown, "RuntimeException must propagate so bugs surface at the tail");
    }

    @Test
    void staysInPipelineAfterHandshakeCompletion() {
        // The whole point of PF-172. PF-109's suppressor removes itself here; this one
        // must not, because post-handshake RSTs are the case being silenced.
        SslSteadyStateExceptionSuppressor suppressor = new SslSteadyStateExceptionSuppressor();
        EmbeddedChannel channel = new EmbeddedChannel(suppressor);
        assertNotNull(channel.pipeline().context(suppressor),
                "precondition: suppressor is in the pipeline");

        channel.pipeline().fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);

        assertNotNull(channel.pipeline().get(SslSteadyStateExceptionSuppressor.class),
                "suppressor must remain after a successful handshake — no self-removal");

        // ...and it must still be doing its job once the handshake window has passed.
        channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
        channel.checkException();
        assertFalse(channel.isOpen(), "post-handshake RST must still be consumed and the channel closed");
    }
}
