package play.libs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class CryptoBcryptTest {

    @BeforeEach
    public void setUp() {
        Properties config = new Properties();
        config.setProperty("application.secret", "testsecretkey1234567890");
        new PlayBuilder().withConfiguration(config).build();
    }

    // --- hashPassword ---

    @Test
    public void hashPasswordProducesBcryptHash() {
        String hash = Crypto.hashPassword("myPassword123");
        assertThat(hash).startsWith("$2");
        assertThat(hash).contains("$12$");
    }

    @Test
    public void hashPasswordWithCustomCost() {
        String hash = Crypto.hashPassword("myPassword123", 4);
        assertThat(hash).startsWith("$2");
        assertThat(hash).contains("$04$");
    }

    @Test
    public void hashPasswordProducesDifferentHashesEachTime() {
        String hash1 = Crypto.hashPassword("myPassword123");
        String hash2 = Crypto.hashPassword("myPassword123");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    // --- checkPassword with bcrypt ---

    @Test
    public void checkPasswordVerifiesBcryptHash() {
        String hash = Crypto.hashPassword("securePassword");
        assertThat(Crypto.checkPassword("securePassword", hash)).isTrue();
    }

    @Test
    public void checkPasswordRejectsWrongPassword() {
        String hash = Crypto.hashPassword("securePassword");
        assertThat(Crypto.checkPassword("wrongPassword", hash)).isFalse();
    }

    @Test
    public void checkPasswordHandlesNullPassword() {
        assertThat(Crypto.checkPassword(null, "$2a$12$somehash")).isFalse();
    }

    @Test
    public void checkPasswordHandlesNullHash() {
        assertThat(Crypto.checkPassword("password", null)).isFalse();
    }

    // --- checkPassword with legacy MD5 ---

    @Test
    @SuppressWarnings("deprecation")
    public void checkPasswordVerifiesLegacyMd5Hash() {
        String legacyHash = Crypto.passwordHash("oldPassword", Crypto.HashType.MD5);
        assertThat(Crypto.checkPassword("oldPassword", legacyHash)).isTrue();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void checkPasswordRejectsWrongPasswordForLegacyHash() {
        String legacyHash = Crypto.passwordHash("oldPassword", Crypto.HashType.MD5);
        assertThat(Crypto.checkPassword("wrongPassword", legacyHash)).isFalse();
    }

    // --- Deprecated methods still work ---

    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedPasswordHashStillWorks() {
        String hash = Crypto.passwordHash("test");
        assertThat(hash).isNotEmpty();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedPasswordHashWithTypeStillWorks() {
        String hash = Crypto.passwordHash("test", Crypto.HashType.SHA256);
        assertThat(hash).isNotEmpty();
    }
}
