package play.mvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;
import play.mvc.Http.*;
import play.mvc.Scope.Session;

import static org.junit.jupiter.api.Assertions.*;


public class SessionTest {

    @BeforeEach
    public void playBuilderBefore() {
        new PlayBuilder().build();
    }

    private static void mockRequestAndResponse() {
        Request.current.set(new Request());
        Response.current.set(new Response());
    }

    public static void setSendOnlyIfChangedConstant(boolean value) {
    	Scope.SESSION_SEND_ONLY_IF_CHANGED = value;
    }

    @Test
    public void testSessionManipulationMethods() {
        mockRequestAndResponse();
        Session session = Session.restore();
        assertFalse(session.changed);

        session.change();
        assertTrue(session.changed);

        // Reset
        session.changed = false;
        session.put("username", "Alice");
        assertTrue(session.changed);

        session.changed = false;
        session.remove("username");
        assertTrue(session.changed);

        session.changed = false;
        session.clear();
        assertTrue(session.changed);
    }

    @Test
    public void testSendOnlyIfChanged() {
        // Mock secret
        Play.secretKey = "0112358";

        Session session;
        setSendOnlyIfChangedConstant(true);
        mockRequestAndResponse();

        // Change nothing in the session
        session = Session.restore();
        session.save();
        assertNull(Response.current().cookies.get(Scope.COOKIE_PREFIX + "_SESSION"));

        mockRequestAndResponse();
        // Change the session
        session = Session.restore();
        session.put("username", "Bob");
        session.save();

        Cookie sessionCookie = Response.current().cookies.get(Scope.COOKIE_PREFIX + "_SESSION");
        assertNotNull(sessionCookie);
        assertTrue(sessionCookie.value.contains("username"));
        assertTrue(sessionCookie.value.contains("Bob"));
    }

    @Test
    public void testSendAlways() {
        Session session;
        setSendOnlyIfChangedConstant(false);

        mockRequestAndResponse();

        // Change nothing in the session
        session = Session.restore();
        session.save();
        assertNotNull(Response.current().cookies.get(Scope.COOKIE_PREFIX + "_SESSION"));
    }

    @Test
    public void defaultSameSiteIsLax() {
        // The class-load default; PlayBuilder uses an empty Properties so
        // application.session.sameSite is unset and the default "Lax" applies.
        assertEquals("Lax", Scope.COOKIE_SAMESITE);
    }

    @Test
    public void sessionCookieHonorsConfiguredSameSite() {
        Play.secretKey = "0112358";
        String original = Scope.COOKIE_SAMESITE;
        try {
            Scope.COOKIE_SAMESITE = "Strict";
            mockRequestAndResponse();
            Session session = Session.restore();
            session.put("username", "Alice");
            session.save();
            Cookie sessionCookie = Response.current().cookies.get(Scope.COOKIE_PREFIX + "_SESSION");
            assertNotNull(sessionCookie);
            assertEquals("Strict", sessionCookie.sameSite);
        } finally {
            Scope.COOKIE_SAMESITE = original;
        }
    }

    @Test
    public void flashCookieHonorsConfiguredSameSite() {
        String original = Scope.COOKIE_SAMESITE;
        try {
            Scope.COOKIE_SAMESITE = "Strict";
            mockRequestAndResponse();
            Scope.Flash flash = new Scope.Flash();
            flash.put("notice", "hi");
            flash.save();
            Cookie flashCookie = Response.current().cookies.get(Scope.COOKIE_PREFIX + "_FLASH");
            assertNotNull(flashCookie);
            assertEquals("Strict", flashCookie.sameSite);
        } finally {
            Scope.COOKIE_SAMESITE = original;
        }
    }

    @Test
    public void noneSameSiteForcesSecureOnSessionCookie() {
        Play.secretKey = "0112358";
        String original = Scope.COOKIE_SAMESITE;
        try {
            // COOKIE_SECURE is final-false by default in the test JVM; the save path
            // still has to upgrade the resulting cookie to Secure when SameSite=None.
            Scope.COOKIE_SAMESITE = "None";
            mockRequestAndResponse();
            Session session = Session.restore();
            session.put("username", "Alice");
            session.save();
            Cookie sessionCookie = Response.current().cookies.get(Scope.COOKIE_PREFIX + "_SESSION");
            assertNotNull(sessionCookie);
            assertEquals("None", sessionCookie.sameSite);
            assertTrue(sessionCookie.secure, "SameSite=None must auto-upgrade Secure");
        } finally {
            Scope.COOKIE_SAMESITE = original;
        }
    }

    @AfterEach
    public void restoreDefault() {
        boolean SESSION_SEND_ONLY_IF_CHANGED = Play.configuration.getProperty("application.session.sendOnlyIfChanged", "false").toLowerCase().equals("true");
        setSendOnlyIfChangedConstant(SESSION_SEND_ONLY_IF_CHANGED);
    }
}
