package play.libs;

import org.jboss.netty.channel.ChannelHandlerContext;

/**
 * Netty 3 implementation of {@link PlayChannel}. Transitional — exists to
 * back the deprecated {@code ChannelHandlerContext}-typed constructors on
 * {@link play.mvc.Http.Inbound} and {@link F.BlockingEventStream}. Will be
 * replaced (or removed) when the framework migrates to Netty 4.
 */
public class NettyPlayChannel implements PlayChannel {

    private final ChannelHandlerContext ctx;

    public NettyPlayChannel(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void setReadable(boolean readable) {
        ctx.getChannel().setReadable(readable);
    }
}
