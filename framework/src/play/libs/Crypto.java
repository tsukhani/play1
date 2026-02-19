package play.libs;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

import play.Play;
import play.exceptions.UnexpectedException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Cryptography utils
 */
public class Crypto {

    /**
     * Define a hash type enumeration for strong-typing
     */
    public enum HashType {
        MD5("MD5"), SHA1("SHA-1"), SHA256("SHA-256"), SHA512("SHA-512");
        private final String algorithm;

        HashType(String algorithm) {
            this.algorithm = algorithm;
        }

        @Override
        public String toString() {
            return this.algorithm;
        }
    }

    /**
     * Set-up MD5 as the default hashing algorithm
     * @deprecated MD5 is not suitable for password hashing. Use {@link #hashPassword(String)} instead.
     */
    @Deprecated
    private static final HashType DEFAULT_HASH_TYPE = HashType.MD5;

    private static final int BCRYPT_DEFAULT_COST = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static final char[] HEX_CHARS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    /**
     * Sign a message using the application secret key (HMAC-SHA1)
     * 
     * @param message
     *            the message to sign
     * @return The signed message
     */
    public static String sign(String message) {
        return sign(message, Play.secretKey.getBytes());
    }

    /**
     * Sign a message with a key
     * 
     * @param message
     *            The message to sign
     * @param key
     *            The key to use
     * @return The signed message (in hexadecimal)
     */
    public static String sign(String message, byte[] key) {

        if (key.length == 0) {
            return message;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec signingKey = new SecretKeySpec(key, "HmacSHA1");
            mac.init(signingKey);
            byte[] messageBytes = message.getBytes(UTF_8);
            byte[] result = mac.doFinal(messageBytes);
            int len = result.length;
            char[] hexChars = new char[len * 2];

            for (int charIndex = 0, startIndex = 0; charIndex < hexChars.length;) {
                int bite = result[startIndex++] & 0xff;
                hexChars[charIndex++] = HEX_CHARS[bite >> 4];
                hexChars[charIndex++] = HEX_CHARS[bite & 0xf];
            }
            return new String(hexChars);
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }

    }

    /**
     * Create a password hash using the default hashing algorithm
     *
     * @param input
     *            The password
     * @return The password hash
     * @deprecated MD5 is not suitable for password hashing. Use {@link #hashPassword(String)} instead.
     */
    @Deprecated
    public static String passwordHash(String input) {
        return passwordHash(input, DEFAULT_HASH_TYPE);
    }

    /**
     * Create a password hash using specific hashing algorithm
     *
     * @param input
     *            The password
     * @param hashType
     *            The hashing algorithm
     * @return The password hash
     * @deprecated Simple message digests are not suitable for password hashing. Use {@link #hashPassword(String)} instead.
     */
    @Deprecated
    public static String passwordHash(String input, HashType hashType) {
        try {
            MessageDigest m = MessageDigest.getInstance(hashType.toString());
            byte[] out = m.digest(input.getBytes());
            return new String(Base64.encodeBase64(out));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Hash a password using bcrypt with a default cost factor of 12.
     * This is the recommended method for password hashing.
     *
     * @param password The plaintext password
     * @return The bcrypt hash string (starting with "$2y$")
     */
    public static String hashPassword(String password) {
        return hashPassword(password, BCRYPT_DEFAULT_COST);
    }

    /**
     * Hash a password using bcrypt with the specified cost factor.
     *
     * @param password The plaintext password
     * @param cost The bcrypt cost factor (4-31, recommended: 10-14)
     * @return The bcrypt hash string (starting with "$2y$")
     */
    public static String hashPassword(String password, int cost) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        return OpenBSDBCrypt.generate(password.toCharArray(), salt, cost);
    }

    /**
     * Verify a password against a hash. Auto-detects bcrypt hashes (starting with "$2")
     * and falls back to legacy MD5 comparison for backward compatibility.
     *
     * @param password The plaintext password to check
     * @param hash The stored hash to check against
     * @return true if the password matches the hash
     */
    public static boolean checkPassword(String password, String hash) {
        if (hash == null || password == null) {
            return false;
        }
        if (hash.startsWith("$2")) {
            // bcrypt hash
            return OpenBSDBCrypt.checkPassword(hash, password.toCharArray());
        }
        // Legacy: compare against MD5 hash for backward compatibility
        return passwordHash(password, HashType.MD5).equals(hash);
    }

    /**
     * Encrypt a String with the AES encryption standard using the application secret
     * 
     * @param value
     *            The String to encrypt
     * @return An hexadecimal encrypted string
     */
    public static String encryptAES(String value) {
        return encryptAES(value, Play.configuration.getProperty("application.secret").substring(0, 16));
    }

    /**
     * Encrypt a String with the AES encryption standard. Private key must have a length of 16 bytes
     * 
     * @param value
     *            The String to encrypt
     * @param privateKey
     *            The key used to encrypt
     * @return An hexadecimal encrypted string
     */
    public static String encryptAES(String value, String privateKey) {
        try {
            byte[] raw = privateKey.getBytes();
            SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
            return Codec.byteToHexString(cipher.doFinal(value.getBytes()));
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    /**
     * Decrypt a String with the AES encryption standard using the application secret
     * 
     * @param value
     *            An hexadecimal encrypted string
     * @return The decrypted String
     */
    public static String decryptAES(String value) {
        return decryptAES(value, Play.configuration.getProperty("application.secret").substring(0, 16));
    }

    /**
     * Decrypt a String with the AES encryption standard. Private key must have a length of 16 bytes
     * 
     * @param value
     *            An hexadecimal encrypted string
     * @param privateKey
     *            The key used to encrypt
     * @return The decrypted String
     */
    public static String decryptAES(String value, String privateKey) {
        try {
            byte[] raw = privateKey.getBytes();
            SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec);
            return new String(cipher.doFinal(Codec.hexStringToByte(value)));
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

}
