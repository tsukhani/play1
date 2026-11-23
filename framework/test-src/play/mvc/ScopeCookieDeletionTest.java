package play.mvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.Play;
import play.PlayBuilder;
import play.data.validation.Validation;
import play.data.validation.ValidationPlugin;
import play.libs.Crypto;
import play.mvc.Http.Cookie;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Scope.Flash;
import play.mvc.Scope.Session;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PF-249: Play deletes an empty session, flash or errors scope's cookie only when the request
 * carried that cookie, whatever {@code application.session.sendOnlyIfChanged} says. A client
 * that sent none has nothing to delete, and before this every response to such a client carried
 * three deletions, about 300 bytes. The rest of the setting's meaning stays: with {@code false} a
 * non-empty session is sent on every response, and with {@code true} only when it changed. Each
 * case runs with the setting off and on.
 */
public class ScopeCookieDeletionTest {

    static final String SESSION = Scope.COOKIE_PREFIX + "_SESSION";
    static final String FLASH = Scope.COOKIE_PREFIX + "_FLASH";
    static final String ERRORS = Scope.COOKIE_PREFIX + "_ERRORS";

    private boolean sendOnlyIfChanged;
    private boolean started;
    private final ValidationPlugin validation = new ValidationPlugin();

    public static void action() {
    }

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
        Play.secretKey = "0112358";
        sendOnlyIfChanged = Scope.SESSION_SEND_ONLY_IF_CHANGED;
        // CookieSessionStore restores a session cookie only once Play has started
        started = Play.started;
        Play.started = true;
    }

    @AfterEach
    public void tearDown() {
        validation.invocationFinally();
        Scope.SESSION_SEND_ONLY_IF_CHANGED = sendOnlyIfChanged;
        Play.started = started;
        Request.current.remove();
        Response.current.remove();
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void aRequestWithoutCookiesGetsNoSetCookie(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of());
        Session session = Session.restore();
        Flash flash = Flash.restore();
        restoreErrors();

        session.save();
        flash.save();
        saveErrors();

        assertTrue(Response.current().cookies.isEmpty(), () -> "set: " + Response.current().cookies.keySet());
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void aClearedSessionTheRequestCarriedIsDeleted(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of(SESSION, signed(Map.of("username", "Alice"))));
        Session session = Session.restore();
        assertEquals("Alice", session.get("username"));

        session.clear();
        session.save();

        assertDeleted(SESSION);
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void aClearedSessionIsDeletedEvenWhenTheRequestDidNotCarryIt(boolean onlyIfChanged) throws Exception {
        // A browser withholds a SameSite cookie from a cross-site POST (and a Strict one from any cross-site
        // navigation) yet takes a deletion in the response: an identity provider's single logout posted to the
        // application is such a request, and its clear() has to reach the browser.
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of());
        Session session = Session.restore();
        session.clear();
        session.save();

        assertDeleted(SESSION);
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void anUntouchedSessionTheRequestDidNotCarryIsNotSent(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of());
        Session session = Session.restore();
        session.get("username");
        session.save();

        assertNull(Response.current().cookies.get(SESSION));
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void anEmptySessionCookieTheRequestCarriedIsDeletedUnlessOnlyChangesAreSent(boolean onlyIfChanged)
            throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        // a cookie whose signature does not match restores as an empty, unchanged session
        request(Map.of(SESSION, "bad-signature-username%3AAlice"));
        Session session = Session.restore();
        assertTrue(session.isEmpty());
        session.save();

        if (onlyIfChanged) {
            assertNull(Response.current().cookies.get(SESSION));
        } else {
            assertDeleted(SESSION);
        }
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void aNonEmptySessionIsSentAsBefore(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        String value = signed(Map.of("username", "Alice"));

        // unchanged: sent on every response with the setting off, not at all with it on
        request(Map.of(SESSION, value));
        Session.restore().save();
        Cookie unchanged = Response.current().cookies.get(SESSION);
        if (onlyIfChanged) {
            assertNull(unchanged);
        } else {
            assertNotNull(unchanged);
            assertEquals(value, unchanged.value);
            assertNull(unchanged.maxAge);
        }

        // changed, or new to a request without the cookie: sent either way
        for (Map<String, String> cookies : List.of(Map.of(SESSION, value), Map.<String, String>of())) {
            request(cookies);
            Session session = Session.restore();
            session.put("theme", "dark");
            session.save();
            Cookie changed = Response.current().cookies.get(SESSION);
            assertNotNull(changed, () -> "request cookies " + cookies.keySet());
            assertTrue(changed.value.contains("theme") && changed.value.contains("dark"), changed.value);
            assertNull(changed.maxAge);
        }
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void aFlashTheRequestCarriedIsDeletedOnceShown(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of(FLASH, CookieDataCodec.encode(Map.of("success", "Saved"))));
        Flash flash = Flash.restore();
        assertEquals("Saved", flash.get("success"));

        flash.save();

        assertDeleted(FLASH);
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void aNonEmptyFlashIsSentAsBefore(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of());
        Flash flash = Flash.restore();
        flash.put("success", "Saved");
        flash.save();

        Cookie sent = Response.current().cookies.get(FLASH);
        assertNotNull(sent);
        assertTrue(sent.value.contains("success") && sent.value.contains("Saved"), sent.value);
        assertNull(sent.maxAge);
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void errorsTheRequestCarriedAreDeletedOnceCleared(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of(ERRORS, URLEncoder.encode("\u0000email:validation.required\u0000", StandardCharsets.UTF_8)));
        restoreErrors();
        assertEquals(1, Validation.errors().size());

        Validation.clear();
        saveErrors();

        assertDeleted(ERRORS);
    }

    @ParameterizedTest(name = "sendOnlyIfChanged={0}")
    @ValueSource(booleans = { false, true })
    public void keptErrorsAreSentAsBefore(boolean onlyIfChanged) throws Exception {
        Scope.SESSION_SEND_ONLY_IF_CHANGED = onlyIfChanged;
        request(Map.of());
        restoreErrors();
        Validation.addError("email", "validation.required");
        Validation.keep();
        saveErrors();

        Cookie sent = Response.current().cookies.get(ERRORS);
        assertNotNull(sent);
        assertTrue(sent.value.contains("email"), sent.value);
        assertNull(sent.maxAge);
    }

    private static void request(Map<String, String> cookies) {
        Request request = new Request();
        cookies.forEach((name, value) -> {
            Cookie cookie = new Cookie();
            cookie.name = name;
            cookie.value = value;
            request.cookies.put(name, cookie);
        });
        Request.current.set(request);
        Response.current.set(new Response());
    }

    private static String signed(Map<String, String> data) throws Exception {
        String encoded = CookieDataCodec.encode(data);
        return Crypto.sign(encoded, Play.secretKey.getBytes()) + "-" + encoded;
    }

    /** What ValidationPlugin does around an action: restore the errors cookie, and save it with the result. */
    private void restoreErrors() throws Exception {
        validation.beforeInvocation();
        Method action = ScopeCookieDeletionTest.class.getMethod("action");
        validation.beforeActionInvocation(action);
    }

    private void saveErrors() {
        validation.onActionInvocationResult(null);
    }

    private static void assertDeleted(String name) {
        Cookie cookie = Response.current().cookies.get(name);
        assertNotNull(cookie, () -> name + " not deleted: " + Response.current().cookies.keySet());
        assertEquals("", cookie.value);
        assertEquals(Integer.valueOf(0), cookie.maxAge);
        assertEquals("/", cookie.path);
    }
}
