package play.server.quic;

import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http3.Http3FrameToHttpObjectCodec;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.stream.ChunkedWriteHandler;
import play.server.StreamChunkAggregator;

/**
 * PF-57: per-stream pipeline for HTTP/3 request streams. Invoked by
 * {@link io.netty.handler.codec.http3.Http3ServerConnectionHandler} once per inbound
 * request stream.
 *
 * <p>Pipeline shape mirrors PF-58's {@code Http2StreamInitializer} exactly — the
 * duplex {@link Http3FrameToHttpObjectCodec} replaces the h2 codec but the rest of
 * the chain ({@link StreamChunkAggregator} → {@link ChunkedWriteHandler} →
 * {@link Http3StreamPlayHandler}) is identical. This is the architectural payoff for
 * Netty's choice to expose both h2 and h3 request streams as standard
 * {@code HttpRequest}/{@code HttpContent}/{@code LastHttpContent} sequences:
 * {@link play.server.PlayHandler}'s routing logic is protocol-version agnostic.
 *
 * <p>The constructor argument {@code true} on {@link Http3FrameToHttpObjectCodec}
 * marks this as the server-side variant — converts inbound headers/data frames into
 * {@code HttpRequest}/{@code HttpContent} and outbound {@code HttpResponse}/{@code HttpContent}
 * back into headers/data frames.
 *
 * <p>Every handler in the per-stream chain holds per-channel state (StreamChunkAggregator's
 * {@code pendingRequest}, Http3StreamPlayHandler's {@code pipelines}, etc.) — none are
 * {@code @Sharable}, so a fresh instance is allocated per stream.
 */
public class Http3StreamInitializer extends ChannelInitializer<QuicStreamChannel> {

    @Override
    protected void initChannel(QuicStreamChannel ch) {
        ch.pipeline()
                .addLast("h3-codec", new Http3FrameToHttpObjectCodec(true))
                .addLast("h3-aggregator", new StreamChunkAggregator())
                .addLast("h3-chunked-write", new ChunkedWriteHandler())
                .addLast("h3-handler", new Http3StreamPlayHandler());
    }
}
