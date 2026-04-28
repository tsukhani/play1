package play.server.ssl;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLException;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import play.Logger;
import play.mvc.Http.Request;
import play.server.PlayHandler;
import play.server.Server;

public class SslPlayHandler extends PlayHandler {

    /** Stores the local socket address from channelActive so SSL exceptions can build a redirect URL. */
    private static final AttributeKey<InetSocketAddress> LOCAL_ADDR =
            AttributeKey.valueOf("play.ssl.localAddress");

    @Override
    public Request parseRequest(ChannelHandlerContext ctx, FullHttpRequest nettyRequest) throws Exception {
        Request request = super.parseRequest(ctx, nettyRequest);
        request.secure = true;
        return request;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Capture the local address so SSL handshake failures can produce a useful redirect.
        ctx.channel().attr(LOCAL_ADDR).set((InetSocketAddress) ctx.channel().localAddress());
        // Get the SslHandler in the current pipeline.
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
        // Note: setEnableRenegotiation was removed in Netty 4 - renegotiation is off by default.
        // Get notified when SSL handshake is done.
        sslHandler.handshakeFuture().addListener(new SslListener());
        super.channelActive(ctx);
    }

    private static final class SslListener implements GenericFutureListener<Future<Channel>> {

        @Override
        public void operationComplete(Future<Channel> future) throws Exception {
            if (!future.isSuccess()) {
                Logger.debug(future.cause(), "Invalid certificate");
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // Audit M30: the previous code tried to send an HTTP redirect after an
        // SSL handshake failure by removing the SslHandler and writing a plain
        // HttpResponse onto the same channel. After remove("ssl"), bytes traverse
        // the pipeline without TLS framing and are emitted as plaintext on a
        // socket the client opened expecting TLS — the client sees garbage and
        // aborts; the redirect is never delivered. There's no way to send a
        // valid HTTP response on a socket that was opened for TLS handshake.
        // Just close the channel; configure a separate plain-HTTP listener on
        // the canonical port if you need the http→https redirect behaviour.
        if (cause instanceof SSLException) {
            Logger.debug(cause, "TLS handshake failure; closing channel");
        } else {
            Logger.error(cause, "");
        }
        ctx.channel().close();
    }
}
