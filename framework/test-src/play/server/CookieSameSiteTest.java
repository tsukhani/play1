package play.server;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import play.mvc.Http;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SameSite attribute of a cookie as it leaves the server. {@code Scope} compares
 * {@code application.session.sameSite} without regard to case when it decides that
 * {@code None} needs {@code Secure}, so the encoder has to read the value the same way: it
 * used to match Netty's enum by exact name, and {@code none} gave a session cookie that was
 * forced to Secure and carried no SameSite attribute at all, while {@code strict} was dropped
 * without a word.
 */
public class CookieSameSiteTest {

    @Test
    public void sameSiteIsEmittedWhateverItsCase() {
        String[][] cases = { { "Lax", "Lax" }, { "lax", "Lax" }, { "Strict", "Strict" }, { "strict", "Strict" },
                { "STRICT", "Strict" }, { "None", "None" }, { "none", "None" } };
        for (String[] c : cases) {
            assertThat(setCookie(c[0])).as("sameSite=%s", c[0]).contains("SameSite=" + c[1]);
        }
    }

    @Test
    public void unknownOrEmptyValueLeavesTheAttributeOut() {
        assertThat(setCookie("off")).doesNotContain("SameSite");
        assertThat(setCookie("")).doesNotContain("SameSite");
        assertThat(setCookie(null)).doesNotContain("SameSite");
    }

    private static String setCookie(String sameSite) {
        Http.Response response = new Http.Response();
        Http.Cookie cookie = new Http.Cookie();
        cookie.name = "PLAY_SESSION";
        cookie.value = "v";
        cookie.secure = true;
        cookie.sameSite = sameSite;
        response.cookies.put(cookie.name, cookie);

        HttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        PlayHandler.addToResponse(response, nettyResponse);
        return nettyResponse.headers().get(HttpHeaderNames.SET_COOKIE);
    }
}
