package play.server.quic;

import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.http3.Http3Settings;
import io.netty.handler.codec.http3.Http3SettingsFrame;
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
        // PF-158: advertise SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8) so clients know they may
        // bootstrap a WebSocket with Extended CONNECT (RFC 9220). Built from
        // Http3Settings.defaultSettings() so the QPACK and field-section defaults Netty would
        // otherwise supply are still advertised, and disableQpackDynamicTable stays true —
        // both are what the one-argument Http3ServerConnectionHandler constructor used before.
        Http3SettingsFrame settings = new DefaultHttp3SettingsFrame(
                Http3Settings.defaultSettings().enableConnectProtocol(true));
        ch.pipeline().addLast("h3-conn",
                new Http3ServerConnectionHandler(streamInitializer, null, null, settings, true));
    }
}
