package play.server.ssl;

import java.io.IOException;
import java.net.SocketException;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PF-109: verifies that the SSL handshake exception suppressor consumes the small
 * set of benign RST/Broken-pipe IOExceptions that browsers routinely produce when
 * tearing down speculative TLS connections, while leaving every other failure mode
 * (real network errors, SSL handshake failures, bugs) propagating to the tail.
 *
 * <p>The suppressor lives in the pipeline only between {@code initChannel} and the
 * first successful {@link SslHandshakeCompletionEvent}; once handshake completes
 * it removes itself so steady-state traffic hits the unchanged catch-all
 * ({@code SslPlayHandler} or per-stream {@code Http2StreamPlayHandler}).
 *
 * <p>{@link EmbeddedChannel}'s pipeline captures unhandled exceptions at its tail
 * — {@code checkException()} rethrows the last captured one — which directly
 * mirrors the "reached at the tail of the pipeline" path we are suppressing in
 * production. Consumed exceptions never reach the tail and {@code checkException()}
 * returns silently.
 */
class SslHandshakeExceptionSuppressorTest {

    @Test
    void consumesConnectionResetAndClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslHandshakeExceptionSuppressor());

        channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));

        channel.checkException();
        assertFalse(channel.isOpen(), "channel must be closed after RST suppression");
    }

    @Test
    void consumesBrokenPipeAndClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslHandshakeExceptionSuppressor());

        channel.pipeline().fireExceptionCaught(new IOException("Broken pipe"));

        channel.checkException();
        assertFalse(channel.isOpen(), "channel must be closed after Broken pipe suppression");
    }

    @Test
    void propagatesUnrelatedSocketExceptionToTail() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslHandshakeExceptionSuppressor());
        SocketException unreachable = new SocketException("Network is unreachable");

        channel.pipeline().fireExceptionCaught(unreachable);

        SocketException thrown = assertThrows(SocketException.class, channel::checkException);
        assertSame(unreachable, thrown,
                "non-benign SocketException must reach the tail unchanged so operators can diagnose");
    }

    @Test
    void propagatesRuntimeExceptionToTail() {
        EmbeddedChannel channel = new EmbeddedChannel(new SslHandshakeExceptionSuppressor());
        RuntimeException bug = new RuntimeException("unexpected");

        channel.pipeline().fireExceptionCaught(bug);

        RuntimeException thrown = assertThrows(RuntimeException.class, channel::checkException);
        assertSame(bug, thrown, "RuntimeException must propagate so bugs surface at the tail");
    }

    @Test
    void propagatesSSLExceptionToTail() {
        // SSLException must propagate so genuine handshake failures still reach
        // SslHandler's trace logging path. The suppressor's job is benign-RST only.
        EmbeddedChannel channel = new EmbeddedChannel(new SslHandshakeExceptionSuppressor());
        SSLException handshake = new SSLException("handshake failed");

        channel.pipeline().fireExceptionCaught(handshake);

        SSLException thrown = assertThrows(SSLException.class, channel::checkException);
        assertSame(handshake, thrown);
    }

    @Test
    void removesSelfOnSuccessfulHandshakeCompletion() {
        SslHandshakeExceptionSuppressor suppressor = new SslHandshakeExceptionSuppressor();
        EmbeddedChannel channel = new EmbeddedChannel(suppressor);
        assertNotNull(channel.pipeline().context(suppressor),
                "precondition: suppressor is in the pipeline");

        channel.pipeline().fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);

        assertNull(channel.pipeline().get(SslHandshakeExceptionSuppressor.class),
                "suppressor must remove itself on successful handshake so steady-state pipeline matches pre-PF-109");
        // Tolerate duplicate completion events (renegotiation) — the second fire must
        // not blow up trying to remove an already-removed handler.
        channel.pipeline().fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);
    }

    @Test
    void retainsSelfOnFailedHandshakeCompletion() {
        // On handshake failure SslHandler closes the channel and surfaces the cause;
        // the suppressor's RST-window job is done, but removing eagerly on a failure
        // event would be a no-op behavior change worth avoiding. The suppressor stays
        // put — the channel close path tears the whole pipeline down regardless.
        SslHandshakeExceptionSuppressor suppressor = new SslHandshakeExceptionSuppressor();
        EmbeddedChannel channel = new EmbeddedChannel(suppressor);

        channel.pipeline().fireUserEventTriggered(
                new SslHandshakeCompletionEvent(new SSLException("handshake failed")));

        assertNotNull(channel.pipeline().get(SslHandshakeExceptionSuppressor.class),
                "suppressor must remain in the pipeline on handshake failure");
    }
}
