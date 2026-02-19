package play.mvc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class SameSiteCookieTest {

    private Properties config;

    @BeforeEach
    public void setUp() {
        config = new Properties();
        new PlayBuilder().withConfiguration(config).build();

        Http.Response response = new Http.Response();
        response.out = new ByteArrayOutputStream();
        Http.Response.current.set(response);

        Http.Request request = Http.Request.createRequest(
                null, "GET", "/", "", null, null, null, null, false, 80, "localhost", false, null, null);
        Http.Request.current.set(request);
    }

    // --- Cookie field tests ---

    @Test
    public void cookieSameSiteFieldDefaultsToNull() {
        Http.Cookie cookie = new Http.Cookie();
        assertThat(cookie.sameSite).isNull();
    }

    @Test
    public void cookieSameSiteFieldCanBeSet() {
        Http.Cookie cookie = new Http.Cookie();
        cookie.sameSite = "Strict";
        assertThat(cookie.sameSite).isEqualTo("Strict");
    }

    // --- setCookie with sameSite ---

    @Test
    public void setCookieWithSameSite() {
        Http.Response response = Http.Response.current();
        response.setCookie("test", "value", null, "/", null, false, false, "Lax");

        Http.Cookie cookie = response.cookies.get("test");
        assertThat(cookie).isNotNull();
        assertThat(cookie.sameSite).isEqualTo("Lax");
    }

    @Test
    public void setCookieWithStrictSameSite() {
        Http.Response response = Http.Response.current();
        response.setCookie("test", "value", null, "/", null, false, false, "Strict");

        Http.Cookie cookie = response.cookies.get("test");
        assertThat(cookie.sameSite).isEqualTo("Strict");
    }

    @Test
    public void setCookieWithNoneSameSiteForceSecure() {
        Http.Response response = Http.Response.current();
        response.setCookie("test", "value", null, "/", null, false, false, "None");

        Http.Cookie cookie = response.cookies.get("test");
        assertThat(cookie.sameSite).isEqualTo("None");
        assertThat(cookie.secure).isTrue();
    }

    @Test
    public void setCookieWithoutSameSiteBackwardsCompatible() {
        Http.Response response = Http.Response.current();
        response.setCookie("test", "value", null, "/", null, false, false);

        Http.Cookie cookie = response.cookies.get("test");
        assertThat(cookie.sameSite).isNull();
    }

    @Test
    public void setCookieNullSameSite() {
        Http.Response response = Http.Response.current();
        response.setCookie("test", "value", null, "/", null, false, false, null);

        Http.Cookie cookie = response.cookies.get("test");
        assertThat(cookie.sameSite).isNull();
    }

    // --- Updating existing cookie ---

    @Test
    public void updatingExistingCookieUpdatesSameSite() {
        Http.Response response = Http.Response.current();
        response.setCookie("test", "value1", null, "/", null, false, false, "Lax");
        response.setCookie("test", "value2", null, "/", null, false, false, "Strict");

        Http.Cookie cookie = response.cookies.get("test");
        assertThat(cookie.value).isEqualTo("value2");
        assertThat(cookie.sameSite).isEqualTo("Strict");
    }

    // --- Simple setCookie overloads don't set sameSite ---

    @Test
    public void simplestSetCookieDoesNotSetSameSite() {
        Http.Response response = Http.Response.current();
        response.setCookie("test", "value");

        Http.Cookie cookie = response.cookies.get("test");
        assertThat(cookie.sameSite).isNull();
    }
}
