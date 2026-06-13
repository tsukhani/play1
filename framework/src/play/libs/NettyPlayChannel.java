package play.libs;

import io.netty.channel.ChannelHandlerContext;

/**
 * Netty 4 implementation of {@link PlayChannel}. Adapts a Netty
 * {@link ChannelHandlerContext} to the transport-neutral readability toggle
 * that {@link play.mvc.Http.Inbound} uses for inbound back-pressure.
 */
public class NettyPlayChannel implements PlayChannel {

    private final ChannelHandlerContext ctx;

    public NettyPlayChannel(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void setReadable(boolean readable) {
        ctx.channel().config().setAutoRead(readable);
    }
}
