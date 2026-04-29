package play.server.ssl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import play.mvc.Http.Request;
import play.server.PlayHandler;

/**
 * Per-stream PlayHandler for HTTP/2. Instantiated fresh per inbound stream by
 * {@link Http2StreamInitializer}; not {@code @Sharable} because PlayHandler carries
 * instance state ({@code pipelines}).
 *
 * <p>HTTP/2 in this fork only rides over TLS, so {@code request.secure} is always
 * true here — same invariant {@link SslPlayHandler#parseRequest} sets on the 1.1 path.
 *
 * <p>SSL handshake plumbing ({@link SslPlayHandler#channelActive}) is intentionally not
 * inherited: SslHandler lives on the parent connection channel, not on stream sub-channels.
 * Inherited {@link PlayHandler#exceptionCaught} closes {@code ctx.channel()}, which on a
 * stream channel sends RST_STREAM and leaves the connection plus sibling streams intact.
 */
public class Http2StreamPlayHandler extends PlayHandler {

    @Override
    public Request parseRequest(ChannelHandlerContext ctx, FullHttpRequest nettyRequest) throws Exception {
        Request request = super.parseRequest(ctx, nettyRequest);
        request.secure = true;
        return request;
    }
}
