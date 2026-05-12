package integration;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * Builds a JKS truststore containing the integration fixture's self-signed
 * test cert (testapp/certs/host.cert) and returns its path. Used by tests that
 * drive {@link play.libs.WS} against the local Netty server over HTTPS —
 * {@code WSAsync} reads {@code ssl.keyStore} / {@code ssl.keyStorePassword}
 * from {@link play.Play#configuration} and wants a JKS file, not raw PEM.
 *
 * <p>Each call produces a fresh temp JKS (deleted on JVM exit). Callers
 * typically wire the result in {@code @BeforeAll} once per test class.
 */
public final class IntegrationTrustStore {

    public static final String PASSWORD = "changeit";

    private IntegrationTrustStore() {}

    public static Path fixtureHostJks() throws Exception {
        File testApp = new File(System.getProperty("user.dir"), "test-src/integration/testapp");
        File pem = new File(testApp, "certs/host.cert");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (var in = Files.newInputStream(pem.toPath())) {
            cert = cf.generateCertificate(in);
        }
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, PASSWORD.toCharArray());
        ks.setCertificateEntry("integration-test-host", cert);
        Path out = Files.createTempFile("integration-truststore", ".jks");
        try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
            ks.store(fos, PASSWORD.toCharArray());
        }
        out.toFile().deleteOnExit();
        return out;
    }
}
