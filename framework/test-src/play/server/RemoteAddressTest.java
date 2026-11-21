package play.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PF-237: {@link PlayHandler#getRemoteIPAddress} compiled its two patterns for every request.
 * They are compiled once now and tried only on an address with the character each needs; the
 * address a request is given must be the one the per-request patterns gave.
 */
public class RemoteAddressTest {

    @Test
    public void addressIsWhatThePatternsGave() throws Exception {
        InetAddress[] addresses = {
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("192.0.2.10"),
                InetAddress.getByName("::1"),
                InetAddress.getByName("2001:db8::1"),
                Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").getAddress(), 5),
        };
        for (InetAddress address : addresses) {
            String hostAddress = address.getHostAddress();
            assertThat(PlayHandler.getRemoteIPAddress(context(address)))
                    .as(hostAddress)
                    .isEqualTo(asThePatternsGaveIt(hostAddress));
        }
        // The scope is cut off, as before.
        assertThat(PlayHandler.getRemoteIPAddress(context(addresses[4]))).isEqualTo("fe80:0:0:0:0:0:0:1");
    }

    @Test
    public void patternsGiveWhatTheyGaveForTheTextsTheyMatch() {
        // getHostAddress() never starts with '/', but the first pattern is kept as it was.
        for (String text : new String[] { "/192.0.2.1:8080", "/192.0.2.1", "a%b", "%", "x", "" }) {
            assertThat(PlayHandler.remoteIPAddressOf(text)).as(text).isEqualTo(asThePatternsGaveIt(text));
        }
    }

    /** The body of getRemoteIPAddress before PF-237. */
    private static String asThePatternsGaveIt(String fullAddress) {
        if (fullAddress.matches("/[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+[:][0-9]+")) {
            fullAddress = fullAddress.substring(1);
            fullAddress = fullAddress.substring(0, fullAddress.indexOf(':'));
        } else if (fullAddress.matches(".*[%].*")) {
            fullAddress = fullAddress.substring(0, fullAddress.indexOf('%'));
        }
        return fullAddress;
    }

    private static ChannelHandlerContext context(InetAddress address) {
        Channel channel = mock(Channel.class);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress(address, 54321));
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        return ctx;
    }
}
