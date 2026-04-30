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
        String hash = Crypto.passwordHashPBKDF2("password");
        assertThat(Crypto.checkPasswordPBKDF2("password", hash)).isTrue();
        assertThat(Crypto.checkPasswordPBKDF2("wrong", hash)).isFalse();
    }

    @Test
    public void testEncryptDecryptWithShortSecret() {
        // The previous derivation NUL-padded short secrets up to 16 bytes; HKDF should
        // produce a uniformly-random-looking key regardless of input length.
        Play.secretKey = "short";
        String original = "tiny secret payload";
        String encrypted = Crypto.encryptAES(original);
        assertThat(Crypto.decryptAES(encrypted)).isEqualTo(original);
    }

    @Test
    public void testEncryptDecryptWithLongSecret() {
        // The previous derivation discarded entropy past byte 16. HKDF compresses the
        // full input into the key, so a 100-char secret round-trips just like a 16-char one.
        Play.secretKey = "x".repeat(100);
        String original = "long-secret payload";
        assertThat(Crypto.decryptAES(Crypto.encryptAES(original))).isEqualTo(original);
    }

    @Test
    public void testHkdfSha256RFC5869TestVector() throws Exception {
        // RFC 5869 Appendix A.1 (basic SHA-256 test vector).
        // IKM = 0x0b * 22, salt = 0x000102...0c, info = 0xf0f1...f9, L = 42.
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = new byte[13];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) i;
        byte[] info = new byte[10];
        for (int i = 0; i < info.length; i++) info[i] = (byte) (0xf0 + i);

        // Use reflection — hkdfSha256 is package-private, accessible from the test.
        java.lang.reflect.Method m = Crypto.class.getDeclaredMethod(
            "hkdfSha256", byte[].class, byte[].class, byte[].class, int.class);
        m.setAccessible(true);
        byte[] okm = (byte[]) m.invoke(null, ikm, salt, info, 42);

        byte[] expected = hexDecode(
            "3cb25f25faacd57a90434f64d0362f2a"
          + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
          + "34007208d5b887185865");
        assertThat(okm).containsExactly(toBoxed(expected));
    }

    private static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static Byte[] toBoxed(byte[] in) {
        Byte[] out = new Byte[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i];
        return out;
    }
}
