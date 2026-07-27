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

    /**
     * PF-160: the starttls channel always validated the chain, but not that the certificate
     * belongs to the host dialled — jakarta.mail 2.0.x defaults checkserveridentity to false,
     * so a trusted certificate issued for any other domain also completed the handshake.
     */
    @Test
    public void starttlsChannelVerifiesServerIdentityByDefault() {
        Play.configuration.setProperty("mail.smtp.channel", "starttls");

        Properties props = Mail.getSession().getProperties();

        assertEquals("25", props.getProperty("mail.smtp.port"));
        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"),
                "a trusted certificate for an unrelated domain must not satisfy this connection");
    }

    /**
     * The opt-out is deliberately narrower on starttls than on ssl: it drops the identity check
     * but must NOT loosen chain validation, which this branch has always performed.
     */
    @Test
    public void starttlsOptOutDropsIdentityCheckWithoutTrustingEverything() {
        Play.configuration.setProperty("mail.smtp.channel", "starttls");
        Play.configuration.setProperty("mail.smtp.ssl.cavalidation", "false");

        Properties props = Mail.getSession().getProperties();

        assertNull(props.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(props.getProperty("mail.smtp.ssl.trust"),
                "opting out of hostname checking must not silently start trusting every certificate");
        assertNull(props.getProperty("mail.smtp.socketFactory.class"));
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
