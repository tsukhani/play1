package play.server;

import java.io.IOException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import play.Logger;

/**
 * PF-110: consumes benign {@link IOException}s ({@code Connection reset},
 * {@code Broken pipe}) on the plain-HTTP pipeline before they can reach the
 * {@code DefaultChannelPipeline} tail and emit the "reached at the tail of the
 * pipeline" WARN. Symmetric to {@code SslHandshakeExceptionSuppressor} (PF-109)
 * which covers the TLS pipeline.
 *
 * <p>Browsers routinely pre-open six speculative TCP connections per host and
 * tear down the losers via TCP RST as soon as a sibling wins (six-per-host
 * parallelism, or h3-vs-h2 races). PF-109 silenced the SSL siblings; the plain
 * siblings on port 9000 still log three resets per page load. Empirically the
 * Netty 4.2 I/O loop ({@code NioIoHandler} plus {@code AdaptivePoolingAllocator})
 * routes the read-side {@link IOException} through a path that {@code PlayHandler}
 * does not consume, so the noise reaches the tail.
 *
 * <p>Scope of the suppression is intentionally narrow — same shape as the SSL
 * counterpart so operators have one mental model:
 * <ul>
 *   <li>{@link IOException} whose message names a reset/broken-pipe condition is
 *       consumed at DEBUG and the channel closed.
 *   <li>Any other {@link Throwable} ({@link RuntimeException}, unrelated
 *       {@code SocketException} like "Network is unreachable") propagates so
 *       misconfiguration and real bugs still surface at the tail.
 * </ul>
 *
 * <p>Unlike PF-109's SSL suppressor this handler does not self-remove: there is
 * no handshake event on the plain-HTTP pipeline, and steady-state RSTs are
 * exactly the case we want to keep silent. The handler is not {@code @Sharable}
 * because pipeline membership is per-channel.
 */
final class PlainHttpExceptionSuppressor extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof IOException && isBenignReset(cause.getMessage())) {
            if (Logger.isDebugEnabled()) {
                Logger.debug("Plain-HTTP connection aborted by peer (%s); closing channel quietly",
                        cause.getMessage());
            }
            ctx.close();
            return;
        }
        ctx.fireExceptionCaught(cause);
    }

    private static boolean isBenignReset(String message) {
        if (message == null) return false;
        return message.contains("Connection reset") || message.contains("Broken pipe");
    }
}
