package play.server.ssl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import play.Logger;

/**
 * Sits at the head of the SSL pipeline (after {@code SslHandler}) and configures the
 * downstream pipeline based on the ALPN protocol negotiated during the TLS handshake.
 *
 * <p>For {@code h2}: installs the HTTP/2 frame codec plus a multiplex handler that
 * fans out streams to {@link Http2StreamInitializer}. PlayHandler is not added at the
 * connection level — it lives inside each stream's sub-pipeline.
 *
 * <p>For {@code http/1.1}: defers to the supplied installer, which runs the same chain
 * build the no-ALPN code path runs today. Keeping the http/1.1 path as a callback (not a
 * branch inside this handler) lets {@code SslHttpServerPipelineFactory} retain its
 * configurable {@code play.ssl.netty.pipeline} chain without duplicating it.
 *
 * <p>Anything else — including the unhelpful {@code ApplicationProtocolNames.NONE} — is
 * a protocol negotiation failure: log and close. Returning to a default chain on
 * unknown ALPN values would silently mask misconfiguration (e.g. a client offering only
 * {@code spdy/3} would otherwise speak HTTP/1.1 with no encoder, producing garbage).
 */
public class Http2OrHttp1Negotiator extends ApplicationProtocolNegotiationHandler {

    /**
     * Functional interface for installing the HTTP/1.1 chain. Distinct from
     * {@link java.util.function.Consumer} because chain construction can throw checked
     * exceptions (e.g. {@code ClassNotFoundException} from reflective handler resolution),
     * matching the {@code throws Exception} signature of the parent
     * {@link io.netty.channel.ChannelInitializer#initChannel}.
     */
    @FunctionalInterface
    public interface Http1ChainInstaller {
        void install(ChannelPipeline pipeline) throws Exception;
    }

    private final Http1ChainInstaller http1ChainInstaller;

    public Http2OrHttp1Negotiator(Http1ChainInstaller http1ChainInstaller) {
        super(ApplicationProtocolNames.HTTP_1_1);
        this.http1ChainInstaller = http1ChainInstaller;
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            Http2FrameCodecBuilder codecBuilder = Http2FrameCodecBuilder.forServer();
            // PF-157: advertise SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8) so clients know they may
            // bootstrap a WebSocket with Extended CONNECT (RFC 8441 §3). Mutated in place rather
            // than assigned from a fresh Http2Settings so Netty's builder defaults
            // (maxHeaderListSize, maxConcurrentStreams) survive.
            codecBuilder.initialSettings().connectProtocolEnabled(true);
            ctx.pipeline()
                    .addLast("h2-frame-codec", codecBuilder.build())
                    .addLast("h2-multiplex", new Http2MultiplexHandler(new Http2StreamInitializer()));
            return;
        }
        if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            http1ChainInstaller.install(ctx.pipeline());
            return;
        }
        Logger.warn("Unsupported ALPN protocol negotiated: %s; closing connection", protocol);
        ctx.close();
    }
}
