package play.libs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;
import play.PlayBuilder;

/**
 * mail.smtp.channel=ssl must verify the SMTP server certificate by default. It previously
 * installed YesSSLSocketFactory unconditionally — which trusts any certificate — so choosing
 * the secure-looking channel silently disabled verification, exposing the message and the SMTP
 * AUTH credentials sent after the handshake (java/insecure-trustmanager).
 */
public class MailSslTest {

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
        Mail.session = null;
    }

    @AfterEach
    public void tearDown() {
        Mail.session = null;
        Play.configuration.remove("mail.smtp.channel");
        Play.configuration.remove("mail.smtp.ssl.cavalidation");
    }

    @Test
    public void sslChannelVerifiesCertificatesByDefault() {
        Play.configuration.setProperty("mail.smtp.channel", "ssl");

        Properties props = Mail.getSession().getProperties();

        assertEquals("465", props.getProperty("mail.smtp.port"));
        assertEquals("true", props.getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"),
                "chain validation alone does not confirm the certificate belongs to this host");
        assertNull(props.getProperty("mail.smtp.socketFactory.class"),
                "the trust-everything socket factory must not be installed by default");
    }

    /** The escape hatch stays available for internal servers with self-signed certificates. */
    @Test
    public void sslChannelHonoursExplicitOptOut() {
        Play.configuration.setProperty("mail.smtp.channel", "ssl");
        Play.configuration.setProperty("mail.smtp.ssl.cavalidation", "false");

        Properties props = Mail.getSession().getProperties();

        assertEquals("play.utils.YesSSLSocketFactory", props.getProperty("mail.smtp.socketFactory.class"));
        assertNull(props.getProperty("mail.smtp.ssl.enable"));
    }
}
