package play.server.ssl;

import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import play.server.StreamChunkAggregator;

/**
 * Per-stream pipeline for HTTP/2 multiplexed streams. Invoked by Netty's
 * {@code Http2MultiplexHandler} once per inbound stream.
 *
 * <p>Pipeline shape mirrors the HTTP/1.1 SSL chain (decoder/encoder + aggregator +
 * chunked-write + handler), but the duplex {@link Http2StreamFrameToHttpObjectCodec}
 * replaces the separate {@code HttpRequestDecoder} and {@code HttpResponseEncoder}.
 * That codec converts inbound {@code Http2StreamFrame}s into the standard
 * {@code HttpRequest}/{@code HttpContent}/{@code LastHttpContent} sequence
 * {@link StreamChunkAggregator} expects, and outbound {@code HttpResponse} objects
 * back into frames — so {@link play.server.PlayHandler}'s routing logic sees the
 * same wire shape it sees on HTTP/1.1.
 *
 * <p>Every handler in the per-stream chain holds per-channel state
 * ({@code StreamChunkAggregator.pendingRequest}, {@code Http2StreamPlayHandler.pipelines},
 * etc.) — none are {@code @Sharable}, so a fresh instance is allocated per stream.
 *
 * <p>The {@code ChunkedWriteHandler} is required for {@link play.server.PlayHandler}'s
 * chunked-response resume logic ({@code ctx.pipeline().get(ChunkedWriteHandler.class)}
 * at PlayHandler.java:1251) to find the handler in the per-stream pipeline.
 */
public class Http2StreamInitializer extends ChannelInitializer<Http2StreamChannel> {

    @Override
    protected void initChannel(Http2StreamChannel ch) {
        ch.pipeline()
                // PF-157: ahead of the HTTP codec, because that codec destroys the pseudo-headers
                // an RFC 8441 Extended CONNECT carries. Removes itself on the first frame of any
                // ordinary request stream.
                .addLast("h2-extended-connect", new Http2ExtendedConnectHandler())
                .addLast("h2-codec", new Http2StreamFrameToHttpObjectCodec(true))
                .addLast("h2-aggregator", new StreamChunkAggregator())
                .addLast("h2-chunked-write", new ChunkedWriteHandler())
                .addLast("h2-handler", new Http2StreamPlayHandler());
    }
}
