package play.libs;

import io.netty.channel.ChannelHandlerContext;

/**
 * Netty 4 implementation of {@link PlayChannel}. Backs the deprecated
 * {@code ChannelHandlerContext}-typed constructors on
 * {@link play.mvc.Http.Inbound} and {@link F.BlockingEventStream}; new
 * code should depend on {@link PlayChannel} directly.
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
