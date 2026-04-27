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
        // We have to redirect to https://, as it was targeting http://
        // Redirect to the root as we don't know the url at that point.
        if (cause instanceof SSLException) {
            Logger.debug(cause, "");
            InetSocketAddress inet = ctx.channel().attr(LOCAL_ADDR).get();
            if (ctx.pipeline().get("ssl") != null) {
                ctx.pipeline().remove("ssl");
            }
            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.TEMPORARY_REDIRECT, Unpooled.EMPTY_BUFFER);
            String host = (inet != null) ? inet.getHostString() : "";
            nettyResponse.headers().set(HttpHeaderNames.LOCATION, "https://" + host + ":" + Server.httpsPort + "/");
            ChannelFuture writeFuture = ctx.writeAndFlush(nettyResponse);
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        } else {
            Logger.error(cause, "");
            ctx.channel().close();
        }
    }
}
