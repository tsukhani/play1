package play.server.quic;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import play.mvc.Http.Request;
import play.server.PlayHandler;

/**
 * PF-57: per-stream {@link PlayHandler} for HTTP/3. Instantiated fresh per inbound
 * QUIC stream by {@link Http3StreamInitializer}; not {@code @Sharable} because PlayHandler
 * carries instance state ({@code pipelines} map at PlayHandler.java:91).
 *
 * <p>HTTP/3 only rides over QUIC (TLS 1.3 inline), so {@code request.secure} is always
 * true here — same invariant {@code SslPlayHandler.parseRequest} sets on the h1 path
 * and {@code Http2StreamPlayHandler.parseRequest} sets on the h2 path.
 *
 * <p>Inherited {@link PlayHandler#exceptionCaught} closes {@code ctx.channel()}, which
 * on a QUIC stream sub-channel sends RST_STREAM and leaves the connection plus sibling
 * streams intact — same scope-correctness PF-58's h2 stream handler relies on.
 */
public class Http3StreamPlayHandler extends PlayHandler {

    @Override
    public Request parseRequest(ChannelHandlerContext ctx, FullHttpRequest nettyRequest) throws Exception {
        Request request = super.parseRequest(ctx, nettyRequest);
        request.secure = true;
        return request;
    }
}
