package play.server;

import io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.utils.Utils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PF-237: the Date header and the Set-Cookie header of an empty cookie (the deletion of the
 * session, flash and errors cookies that Play sends on every response that does not set them)
 * are the same text for every response of a wall-clock second. {@link PlayHandler} formats each
 * once a second instead of once per response; what it sends must stay exactly what the
 * formatter and Netty's encoder give.
 */
public class ResponseHeadersPerSecondTest {

    @BeforeEach
    public void forget() {
        PlayHandler.emptyCookies.clear();
    }

    @Test
    public void dateIsWhatTheFormatterGivesForThatSecond() {
        long start = System.currentTimeMillis();
        for (long millis = start - 5_000; millis < start + 5_000; millis += 137) {
            assertThat(PlayHandler.httpDate(millis)).isEqualTo(Utils.formatHttpDate(new Date(millis)));
        }
        // Either side of a second boundary, and before the epoch, where the second rounds down.
        long boundary = (start / 1000 + 1) * 1000;
        assertThat(PlayHandler.httpDate(boundary - 1)).isEqualTo(Utils.formatHttpDate(new Date(boundary - 1)));
        assertThat(PlayHandler.httpDate(boundary)).isEqualTo(Utils.formatHttpDate(new Date(boundary)));
        assertThat(PlayHandler.httpDate(-1)).isEqualTo(Utils.formatHttpDate(new Date(-1)));
        assertThat(PlayHandler.httpDateNow()).isNotBlank();
    }

    @Test
    public void dateIsFormattedOncePerSecond() {
        long second = (System.currentTimeMillis() / 1000 + 10) * 1000;
        String first = PlayHandler.httpDate(second + 1);
        assertThat(PlayHandler.httpDate(second + 999)).isSameAs(first);
        assertThat(PlayHandler.httpDate(second + 1000)).isNotEqualTo(first);
    }

    @Test
    public void emptyCookieIsWhatTheEncoderGives() {
        for (boolean secure : new boolean[] { false, true }) {
            for (boolean httpOnly : new boolean[] { false, true }) {
                for (SameSite sameSite : new SameSite[] { null, SameSite.Lax, SameSite.Strict, SameSite.None }) {
                    for (String domain : new String[] { null, "example.com" }) {
                        DefaultCookie c = deletion("PLAY_FLASH");
                        c.setSecure(secure);
                        c.setHttpOnly(httpOnly);
                        c.setSameSite(sameSite);
                        c.setDomain(domain);
                        assertSameAsEncoder(c);
                    }
                }
            }
        }
        // No Max-Age: no Expires either.
        DefaultCookie noMaxAge = new DefaultCookie("PLAY_ERRORS", "");
        noMaxAge.setPath("/");
        assertSameAsEncoder(noMaxAge);
    }

    @Test
    public void emptyCookieIsEncodedOncePerSecond() {
        String first = PlayHandler.encodeCookie(deletion("PLAY_SESSION"));
        String second = PlayHandler.encodeCookie(deletion("PLAY_SESSION"));
        if (!first.equals(second)) {
            // The second ticked between the two: both were encoded, and the second one is kept.
            first = second;
            second = PlayHandler.encodeCookie(deletion("PLAY_SESSION"));
        }
        assertThat(second).isSameAs(first);

        // Another attribute is another header.
        DefaultCookie strict = deletion("PLAY_SESSION");
        strict.setSameSite(SameSite.Strict);
        assertThat(PlayHandler.encodeCookie(strict)).contains("SameSite=Strict").isNotSameAs(first);
    }

    @Test
    public void cookieWithAValueIsEncodedEachTimeAndNotKept() {
        DefaultCookie session = new DefaultCookie("PLAY_SESSION", "abc-def");
        session.setPath("/");
        String first = PlayHandler.encodeCookie(session);
        assertThat(first).isEqualTo(ServerCookieEncoder.STRICT.encode(session));
        assertThat(PlayHandler.encodeCookie(session)).isEqualTo(first).isNotSameAs(first);
        assertThat(PlayHandler.emptyCookies).isEmpty();
    }

    @Test
    public void keptHeadersAreBounded() {
        for (int i = 0; i < PlayHandler.EMPTY_COOKIES_MAX * 2; i++) {
            DefaultCookie c = deletion("app_" + i);
            assertSameAsEncoder(c);
        }
        assertThat(PlayHandler.emptyCookies).hasSizeLessThanOrEqualTo(PlayHandler.EMPTY_COOKIES_MAX);
    }

    @Test
    public void anInvalidNameIsStillRefused() {
        assertThatThrownBy(() -> PlayHandler.encodeCookie(deletion("bad name;")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PlayHandler.emptyCookies).isEmpty();
    }

    private static DefaultCookie deletion(String name) {
        DefaultCookie c = new DefaultCookie(name, "");
        c.setPath("/");
        c.setMaxAge(0);
        c.setSameSite(SameSite.Lax);
        return c;
    }

    /** The two are encoded within one second; a tick in between gives another Expires, so retry. */
    private static void assertSameAsEncoder(DefaultCookie c) {
        for (int attempt = 0; ; attempt++) {
            long before = System.currentTimeMillis() / 1000;
            String ours = PlayHandler.encodeCookie(c);
            String netty = ServerCookieEncoder.STRICT.encode(c);
            if (System.currentTimeMillis() / 1000 == before || attempt == 3) {
                assertThat(ours).isEqualTo(netty);
                return;
            }
        }
    }
}
