package play.server.ssl;

import java.io.IOException;

import javax.net.ssl.SSLException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

import play.Logger;

/**
 * PF-109: consumes benign IOExceptions that fire on the SSL pipeline during the TLS
 * handshake window, before {@link SslPlayHandler} (or per-stream
 * {@link Http2StreamPlayHandler}) has been installed as the catch-all.
 *
 * <p>Browsers that race HTTP/3 against HTTP/1.1+h2, or pre-open speculative TCP
 * connections for six-per-host parallelism, routinely tear down half-opened TLS
 * connections with TCP RST once a sibling negotiates h2 via ALPN. Without this
 * handler the RST surfaces at {@code DefaultChannelPipeline}'s tail as
 * {@code java.net.SocketException: Connection reset} logged at WARN — multiple
 * times per page load — even though the application has done nothing wrong and no
 * resources leak.
 *
 * <p>Scope of the suppression is intentionally narrow:
 * <ul>
 *   <li>{@link IOException} whose message names a reset/broken-pipe condition is
 *       consumed at DEBUG and the channel closed.
 *   <li>{@link SSLException} propagates so genuine handshake failures still reach
 *       {@code SslHandler}'s trace logging path.
 *   <li>Any other {@link Throwable} (e.g. {@link RuntimeException}, unrelated
 *       {@code SocketException} like "Network is unreachable") propagates so
 *       misconfiguration and real bugs still surface at the tail.
 * </ul>
 *
 * <p>Self-removal: on a successful {@link SslHandshakeCompletionEvent} the handler
 * removes itself from the pipeline, so steady-state traffic is unaffected. The
 * event is re-fired upstream first so {@code Http2OrHttp1Negotiator}'s own
 * removal logic still runs. The handler is not {@code @Sharable} because its
 * pipeline membership is mutated per channel.
 */
final class SslHandshakeExceptionSuppressor extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof SSLException) {
            ctx.fireExceptionCaught(cause);
            return;
        }
        if (cause instanceof IOException && isBenignReset(cause.getMessage())) {
            if (Logger.isDebugEnabled()) {
                Logger.debug("SSL handshake aborted by peer (%s); closing channel quietly",
                        cause.getMessage());
            }
            ctx.close();
            return;
        }
        ctx.fireExceptionCaught(cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent) evt).isSuccess()) {
            // Propagate first so the ALPN negotiator (which also listens for this event)
            // still removes itself, then take ourselves out. The null check tolerates
            // duplicate completion events from renegotiation.
            ctx.fireUserEventTriggered(evt);
            if (ctx.pipeline().context(this) != null) {
                ctx.pipeline().remove(this);
            }
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    private static boolean isBenignReset(String message) {
        if (message == null) return false;
        return message.contains("Connection reset") || message.contains("Broken pipe");
    }
}
