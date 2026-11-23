package play.server.ssl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-244: an HTTP/2 connection consolidates its streams' flushes between {@code SslHandler} and the HTTP/2 codec,
 * so that the responses written in one turn of the event loop are wrapped into TLS records together.
 */
public class Http2OrHttp1NegotiatorTest {

    @Test
    public void h2ConsolidatesFlushesAheadOfTheFrameCodec() throws Exception {
        Http2OrHttp1Negotiator negotiator = new Http2OrHttp1Negotiator(pipeline -> {
            throw new AssertionError("the HTTP/1.1 chain must not be installed for h2");
        });
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("alpn", negotiator);
        ChannelHandlerContext ctx = channel.pipeline().context("alpn");

        negotiator.configurePipeline(ctx, ApplicationProtocolNames.HTTP_2);

        List<String> names = channel.pipeline().names();
        assertThat(names).containsSubsequence("alpn", "h2-flush-consolidation", "h2-frame-codec", "h2-multiplex");
        assertThat(names.indexOf("h2-frame-codec")).isEqualTo(names.indexOf("h2-flush-consolidation") + 1);
        assertThat(channel.pipeline().get("h2-flush-consolidation")).isInstanceOf(FlushConsolidationHandler.class);
        assertThat(channel.pipeline().get("h2-frame-codec")).isInstanceOf(Http2FrameCodec.class);
        channel.finishAndReleaseAll();
    }

    @Test
    public void http11GetsItsOwnChainWithoutConsolidation() throws Exception {
        boolean[] installed = {false};
        Http2OrHttp1Negotiator negotiator = new Http2OrHttp1Negotiator(pipeline -> installed[0] = true);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("alpn", negotiator);

        negotiator.configurePipeline(channel.pipeline().context("alpn"), ApplicationProtocolNames.HTTP_1_1);

        assertThat(installed[0]).isTrue();
        assertThat(channel.pipeline().get("h2-flush-consolidation")).isNull();
        channel.finishAndReleaseAll();
    }
}
