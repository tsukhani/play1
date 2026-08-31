package play.server.ssl;

import java.io.IOException;

import javax.net.ssl.SSLException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import play.Logger;
import play.server.BenignIoExceptions;

/**
 * PF-172: consumes benign {@link IOException}s ({@code Connection reset},
 * {@code Broken pipe}) that fire on the TLS pipeline <em>after</em> the handshake
 * has completed. Closes the gap between the two existing suppressors:
 * {@link SslHandshakeExceptionSuppressor} (PF-109) covers the TLS handshake window
 * but removes itself on a successful {@code SslHandshakeCompletionEvent}, and
 * {@code PlainHttpExceptionSuppressor} (PF-110) covers the steady state but is only
 * installed on the plain-HTTP pipeline. Steady-state resets on the HTTPS port fell
 * between the two.
 *
 * <p>The noise has two shapes depending on the negotiated protocol, and this handler
 * sits above the split so one instance covers both:
 * <ul>
 *   <li><b>h2</b> — the parent channel has no catch-all at all ({@code PlayHandler}
 *       lives inside each child stream pipeline, not on the connection), and
 *       {@code Http2ConnectionHandler} re-fires any exception with no embedded
 *       {@code Http2Exception}. The reset reaches {@code DefaultChannelPipeline}'s
 *       tail and logs the "reached at the tail of the pipeline" WARN.
 *   <li><b>http/1.1</b> — {@link SslPlayHandler#exceptionCaught} consumes it, but at
 *       {@code Logger.error(cause, "")}: an empty-message ERROR plus stack trace.
 * </ul>
 *
 * <p>Cause is client-side and routine: browsers pre-open speculative connections per
 * host and tear down the losers with TCP RST as soon as a sibling wins — six-per-host
 * parallelism, or an h3-vs-h2 race. Nothing is served incorrectly and the channel is
 * closed either way; the cost is that a real pipeline fault on the HTTPS port is hard
 * to spot among routine resets.
 *
 * <p>Scope of the suppression is intentionally narrow — same shape as the PF-109 and
 * PF-110 counterparts so operators have one mental model:
 * <ul>
 *   <li>{@link IOException} whose message names a reset/broken-pipe condition is
 *       consumed at DEBUG and the channel closed.
 *   <li>{@link SSLException} propagates (checked first, since it is itself an
 *       {@link IOException} and its message can name a reset) so genuine TLS faults
 *       still surface.
 *   <li>Any other {@link Throwable} ({@link RuntimeException}, unrelated
 *       {@code SocketException} like "Network is unreachable") propagates so
 *       misconfiguration and real bugs still reach the tail.
 * </ul>
 *
 * <p>Unlike PF-109's handshake suppressor this handler does not self-remove: the
 * steady state is precisely the window it exists to keep quiet. The handler is not
 * {@code @Sharable} because pipeline membership is per-channel.
 */
final class SslSteadyStateExceptionSuppressor extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof SSLException) {
            ctx.fireExceptionCaught(cause);
            return;
        }
        if (BenignIoExceptions.isBenignReset(cause)) {
            if (Logger.isDebugEnabled()) {
                Logger.debug("TLS connection aborted by peer (%s); closing channel quietly",
                        cause.getMessage());
            }
            ctx.close();
            return;
        }
        ctx.fireExceptionCaught(cause);
    }
}
