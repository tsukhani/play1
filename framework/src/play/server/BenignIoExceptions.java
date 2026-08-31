package play.server;

import java.io.IOException;

/**
 * Shared predicate for the "peer went away" IOExceptions that the pipeline exception
 * suppressors consume at DEBUG rather than letting them reach the
 * {@code DefaultChannelPipeline} tail: {@link PlainHttpExceptionSuppressor} (PF-110),
 * {@code play.server.ssl.SslHandshakeExceptionSuppressor} (PF-109) and
 * {@code play.server.ssl.SslSteadyStateExceptionSuppressor} (PF-172).
 *
 * <p>Extracted when PF-172 made the third copy: each suppressor carried its own private
 * version, so widening the match — the obvious next change is some peer's phrasing that
 * neither literal covers — meant finding all three or silently fixing two pipelines out
 * of three. The call sites keep their own policy <em>around</em> this predicate: both SSL
 * suppressors test for {@code SSLException} first so genuine TLS faults still propagate.
 * That ordering is a pipeline concern, not a property of the exception, so it stays where
 * it is explained.
 *
 * <p>The match is on message text because the JDK offers no distinct type for these: a
 * peer RST surfaces as {@code java.net.SocketException: Connection reset} thrown from
 * {@code sun.nio.ch.SocketChannelImpl.throwConnectionReset}, and that same
 * {@code SocketException} type also carries genuinely diagnostic messages such as
 * "Network is unreachable" which must stay visible. Deliberately not a cause-chain walk:
 * every occurrence observed in PF-109, PF-110 and PF-172 carries the message on the
 * thrown exception itself, and following nested causes would widen the silence past what
 * those reports justify.
 */
public final class BenignIoExceptions {

    private BenignIoExceptions() {}

    /**
     * True when {@code cause} is an {@link IOException} whose message names a peer-side
     * connection teardown ({@code Connection reset} / {@code Broken pipe}) — noise the
     * server can neither prevent nor act on, since the connection is already gone.
     *
     * <p>Note that {@link javax.net.ssl.SSLException} <em>is</em> an {@link IOException}
     * and can carry such a message ("Connection reset by peer"), so this returns true for
     * one. Callers that must keep TLS faults visible check for {@code SSLException} before
     * calling.
     */
    public static boolean isBenignReset(Throwable cause) {
        if (!(cause instanceof IOException)) return false;
        String message = cause.getMessage();
        return message != null
                && (message.contains("Connection reset") || message.contains("Broken pipe"));
    }
}
