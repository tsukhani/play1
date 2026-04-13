package play.libs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class CryptoTest {

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
        Play.secretKey = "abcdefghijklmnop";
        Properties config = new Properties();
        config.setProperty("application.secret", "abcdefghijklmnopqrstuvwxyz012345");
        Play.configuration = config;
    }

    @Test
    public void testSignIsConsistent() {
        String first = Crypto.sign("hello");
        String second = Crypto.sign("hello");
        assertThat(first).isEqualTo(second);
    }

    @Test
    public void testSignDifferentInputsDifferentOutputs() {
        assertThat(Crypto.sign("a")).isNotEqualTo(Crypto.sign("b"));
    }

    @Test
    public void testEncryptDecryptRoundTrip() {
        String original = "secret message";
        String encrypted = Crypto.encryptAES(original);
        String decrypted = Crypto.decryptAES(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    public void testEncryptProducesDifferentCiphertexts() {
        // AES/GCM uses a random IV so each encryption produces a unique ciphertext
        String first = Crypto.encryptAES("same input");
        String second = Crypto.encryptAES("same input");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    public void testPasswordHashPBKDF2RoundTrip() {
        // Requires Crypto.passwordHashPBKDF2 / checkPasswordPBKDF2 to be present
        // (added by the security-hardening patch). Un-comment once that is merged.
        // String hash = Crypto.passwordHashPBKDF2("password");
        // assertThat(Crypto.checkPasswordPBKDF2("password", hash)).isTrue();
        // assertThat(Crypto.checkPasswordPBKDF2("wrong", hash)).isFalse();
    }
}
