package play.server;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecurityHeadersPolicy}. Exercises the policy directly against
 * a fake Netty {@link HttpHeaders} — no {@code Http.Request} or {@code Http.Response}
 * plumbing required.
 */
public class SecurityHeadersPolicyTest {

    @AfterEach
    public void resetPolicy() {
        SecurityHeadersPolicy.install(SecurityHeadersPolicy.DISABLED);
    }

    @Test
    public void allDefaultHeadersPresentWhenEnabled() {
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(new Properties());
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, false);

        assertThat(headers.get("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.get("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.get("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(headers.get("X-XSS-Protection")).isEqualTo("0");
        assertThat(headers.get("Content-Security-Policy")).isEqualTo("default-src 'self'");
    }

    @Test
    public void hstsAbsentOnPlainHttp() {
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(new Properties());
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, false);

        assertThat(headers.contains("Strict-Transport-Security")).isFalse();
    }

    @Test
    public void hstsPresentOnHttps() {
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(new Properties());
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, true);

        assertThat(headers.get("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    public void disabledMasterSwitchYieldsNoHeaders() {
        Properties config = new Properties();
        config.setProperty("http.headers.enabled", "false");
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(config);
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, true);

        assertThat(headers.size()).isZero();
        assertThat(policy).isSameAs(SecurityHeadersPolicy.DISABLED);
    }

    @Test
    public void existingHeadersAreNotOverwritten() {
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(new Properties());
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("X-Frame-Options", "SAMEORIGIN");

        policy.applyTo(headers, false);

        assertThat(headers.get("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(headers.get("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    public void caseInsensitiveOverrideRespected() {
        // Netty HttpHeaders is case-insensitive — an app that set lowercase still wins.
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(new Properties());
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("x-frame-options", "ALLOW-FROM https://example.com");

        policy.applyTo(headers, false);

        assertThat(headers.get("X-Frame-Options")).isEqualTo("ALLOW-FROM https://example.com");
    }

    @Test
    public void individualHeaderDisabledViaEmptyString() {
        Properties config = new Properties();
        config.setProperty("http.headers.xFrameOptions", "");
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(config);
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, false);

        assertThat(headers.contains("X-Frame-Options")).isFalse();
        assertThat(headers.get("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    public void individualHeaderDisabledViaKeyword() {
        Properties config = new Properties();
        config.setProperty("http.headers.contentSecurityPolicy", "disabled");
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(config);
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, false);

        assertThat(headers.contains("Content-Security-Policy")).isFalse();
    }

    @Test
    public void customHeaderValuesFromConfig() {
        Properties config = new Properties();
        config.setProperty("http.headers.xFrameOptions", "SAMEORIGIN");
        config.setProperty("http.headers.referrerPolicy", "no-referrer");
        config.setProperty("http.headers.contentSecurityPolicy", "default-src 'self'; script-src 'self'");
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(config);
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, false);

        assertThat(headers.get("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(headers.get("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.get("Content-Security-Policy")).isEqualTo("default-src 'self'; script-src 'self'");
    }

    @Test
    public void hstsCustomMaxAgeAndFlags() {
        Properties config = new Properties();
        config.setProperty("http.headers.hsts.maxAge", "86400");
        config.setProperty("http.headers.hsts.preload", "true");
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(config);
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, true);

        assertThat(headers.get("Strict-Transport-Security"))
                .isEqualTo("max-age=86400; includeSubDomains; preload");
    }

    @Test
    public void hstsIncludeSubDomainsCanBeDisabled() {
        Properties config = new Properties();
        config.setProperty("http.headers.hsts.includeSubDomains", "false");
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(config);
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, true);

        assertThat(headers.get("Strict-Transport-Security")).isEqualTo("max-age=31536000");
    }

    @Test
    public void hstsCanBeDisabledIndependently() {
        Properties config = new Properties();
        config.setProperty("http.headers.hsts.enabled", "false");
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(config);
        HttpHeaders headers = new DefaultHttpHeaders();

        policy.applyTo(headers, true);

        assertThat(headers.contains("Strict-Transport-Security")).isFalse();
        assertThat(headers.get("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    public void existingHstsHeaderIsNotOverwritten() {
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(new Properties());
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Strict-Transport-Security", "max-age=0");

        policy.applyTo(headers, true);

        assertThat(headers.get("Strict-Transport-Security")).isEqualTo("max-age=0");
    }

    @Test
    public void nullHeadersIsTolerated() {
        SecurityHeadersPolicy.fromConfig(new Properties()).applyTo(null, true);
        // no exception
    }

    @Test
    public void installAndCurrentRoundtrip() {
        SecurityHeadersPolicy policy = SecurityHeadersPolicy.fromConfig(new Properties());
        SecurityHeadersPolicy.install(policy);
        assertThat(SecurityHeadersPolicy.current()).isSameAs(policy);

        SecurityHeadersPolicy.install(null);
        assertThat(SecurityHeadersPolicy.current()).isSameAs(SecurityHeadersPolicy.DISABLED);
    }
}
