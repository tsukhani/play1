package play.server.quic;

import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.QuicChannel;

/**
 * PF-57: per-connection initializer for incoming QUIC connections. Wires
 * {@link Http3ServerConnectionHandler} onto each accepted {@link QuicChannel};
 * that handler in turn fans out per-stream initialization to {@link Http3StreamInitializer}
 * for each inbound request stream the QUIC peer opens.
 *
 * <p>One {@link Http3ServerConnectionHandler} per QUIC connection (it carries connection-level
 * settings + qpack table state); one {@link Http3StreamInitializer} reused across all
 * streams of all connections (it allocates fresh per-stream handler instances internally).
 */
public class Http3ServerInitializer extends ChannelInitializer<QuicChannel> {

    private final Http3StreamInitializer streamInitializer = new Http3StreamInitializer();

    @Override
    protected void initChannel(QuicChannel ch) {
        ch.pipeline().addLast("h3-conn", new Http3ServerConnectionHandler(streamInitializer));
    }
}
