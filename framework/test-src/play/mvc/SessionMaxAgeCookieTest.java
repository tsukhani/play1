package play.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import play.Play;
import play.PlayBuilder;
import play.mvc.Http.Cookie;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Scope.Session;

/**
 * PF-249 with {@code application.session.maxAge} set, as applications commonly have it: a request without the session
 * cookie gets a fresh session with the expiry timestamp in it, and that stamp must not count as the action changing
 * the session, or every such response carries the session cookie's deletion again. A logout's {@code clear()} still
 * deletes it. {@code Scope.COOKIE_EXPIRE} is read once, when {@code Scope} is first loaded, and the unit tests share
 * one JVM, so each case runs in a JVM of its own.
 */
public class SessionMaxAgeCookieTest {

    @ParameterizedTest(name = "{0}, sendOnlyIfChanged={1}")
    @CsvSource({ "untouched,false,none", "untouched,true,none", "cleared,false,deleted", "cleared,true,deleted" })
    public void theExpiryStampOfAFreshSessionIsNoChangeButALogoutIs(String action, boolean onlyIfChanged, String sent)
            throws Exception {
        Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-cp",
                System.getProperty("java.class.path"), Child.class.getName(), action, Boolean.toString(onlyIfChanged))
                .redirectErrorStream(true).start();
        assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
        List<String> out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        assertThat(out).as("the child ran with the expiry set").contains("expire=1h");
        assertThat(out).as("what the response carried").contains("session cookie: " + sent);
    }

    /** One request without cookies, with {@code application.session.maxAge=1h} set before Scope is loaded. */
    public static final class Child {
        public static void main(String[] args) {
            new PlayBuilder().build();
            Play.configuration.setProperty("application.session.maxAge", "1h");
            Play.secretKey = "0112358";
            Play.started = true;
            System.out.println("expire=" + Scope.COOKIE_EXPIRE);
            Scope.SESSION_SEND_ONLY_IF_CHANGED = Boolean.parseBoolean(args[1]);
            Request.current.set(new Request());
            Response.current.set(new Response());
            Session session = Session.restore();
            if (args[0].equals("cleared")) {
                session.clear();
            }
            session.save();
            Cookie cookie = Response.current().cookies.get(Scope.COOKIE_PREFIX + "_SESSION");
            System.out.println("session cookie: " + (cookie == null ? "none" : cookie.value.isEmpty() ? "deleted" : "sent"));
        }
    }
}
