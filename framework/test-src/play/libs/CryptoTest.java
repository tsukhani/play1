package play.libs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    public void testDecryptAESRejectsLegacyCiphertextWithMigrationMessage() {
        // Produce genuine legacy (1.12-shaped) ciphertext: hex-encoded AES/ECB via the
        // deprecated two-arg encryptAES. decryptAES(String) must detect this shape and refuse
        // with an actionable migration message rather than a generic "Decryption failed".
        String legacy = Crypto.encryptAES("legacy payload", "abcdefghijklmnop");
        assertThat(legacy).matches("[0-9a-fA-F]+");

        Throwable thrown = catchThrowable(() -> Crypto.decryptAES(legacy));
        assertThat(thrown).isNotNull();
        String message = thrown.getMessage() + " " + String.valueOf(thrown.getCause());
        assertThat(message)
                .contains("HKDF-SHA256")
                .contains("legacy")
                .containsIgnoringCase("re-encrypt");
    }

    @Test
    public void testDecryptAESLegacyDetectionDoesNotBreakRoundTrip() {
        // Regression guard: the legacy-detection short-circuit must not affect the happy path.
        String original = "fresh secret message";
        String encrypted = Crypto.encryptAES(original);
        assertThat(Crypto.decryptAES(encrypted)).isEqualTo(original);
    }

    @Test
    public void testPasswordHashPBKDF2RoundTrip() {
        String hash = Crypto.passwordHashPBKDF2("password");
        assertThat(Crypto.checkPasswordPBKDF2("password", hash)).isTrue();
        assertThat(Crypto.checkPasswordPBKDF2("wrong", hash)).isFalse();
    }

    @Test
    public void testCheckPasswordLegacyVerifiesMd5Hash() throws Exception {
        // Legacy Play 1.x default: passwordHash(input) == Base64(MD5(input)).
        // Compute the expected stored value independently (do NOT call the helper under test).
        String password = "s3cr3t";
        String storedMd5 = independentLegacyHash("MD5", password);
        assertThat(Crypto.checkPasswordLegacy(password, storedMd5)).isTrue();
    }

    @Test
    public void testCheckPasswordLegacyVerifiesSha256Hash() throws Exception {
        // Legacy passwordHash(input, HashType.SHA256) == Base64(SHA-256(input)).
        String password = "s3cr3t";
        String storedSha256 = independentLegacyHash("SHA-256", password);
        assertThat(Crypto.checkPasswordLegacy(password, storedSha256)).isTrue();
    }

    @Test
    public void testCheckPasswordLegacyVerifiesSha1AndSha512Hashes() throws Exception {
        // Round out coverage of the documented legacy algorithm set.
        String password = "s3cr3t";
        assertThat(Crypto.checkPasswordLegacy(password, independentLegacyHash("SHA-1", password))).isTrue();
        assertThat(Crypto.checkPasswordLegacy(password, independentLegacyHash("SHA-512", password))).isTrue();
    }

    @Test
    public void testCheckPasswordLegacyRejectsWrongPassword() throws Exception {
        String storedMd5 = independentLegacyHash("MD5", "s3cr3t");
        assertThat(Crypto.checkPasswordLegacy("wrong", storedMd5)).isFalse();
        assertThat(Crypto.checkPasswordLegacy(null, storedMd5)).isFalse();
        assertThat(Crypto.checkPasswordLegacy("s3cr3t", null)).isFalse();
    }

    @Test
    public void testCheckPasswordLegacyKnownVector() {
        // Hard-coded, hand-verified Base64 MD5 of "password":
        //   echo -n password | openssl dgst -md5 -binary | base64  ->  X03MO1qnZdYdgyfeuILPmQ==
        assertThat(Crypto.checkPasswordLegacy("password", "X03MO1qnZdYdgyfeuILPmQ==")).isTrue();
        assertThat(Crypto.checkPasswordLegacy("Password", "X03MO1qnZdYdgyfeuILPmQ==")).isFalse();
    }

    @Test
    public void testPbkdf2PathUnaffectedByLegacyHelper() {
        // The new PBKDF2 verify path must keep working and must NOT match a legacy digest, and the
        // legacy helper must NOT match a PBKDF2 string — the two formats stay disjoint.
        String pbkdf2 = Crypto.passwordHashPBKDF2("password");
        assertThat(Crypto.checkPasswordPBKDF2("password", pbkdf2)).isTrue();
        assertThat(Crypto.checkPasswordLegacy("password", pbkdf2)).isFalse();

        String legacyMd5 = "X03MO1qnZdYdgyfeuILPmQ==";
        assertThat(Crypto.checkPasswordPBKDF2("password", legacyMd5)).isFalse();
    }

    /** Reproduce a legacy Base64 digest independently of the production helper. */
    private static String independentLegacyHash(String algorithm, String input) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance(algorithm)
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(digest);
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
