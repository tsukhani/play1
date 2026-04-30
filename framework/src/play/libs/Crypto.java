package play.libs;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;

import play.Play;
import play.exceptions.UnexpectedException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Cryptography utils
 */
public class Crypto {

    static final char[] HEX_CHARS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    /**
     * Sign a message using the application secret key (HMAC-SHA256)
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
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec signingKey = new SecretKeySpec(key, "HmacSHA256");
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
     * Create a password hash using PBKDF2WithHmacSHA256 with a random salt.
     *
     * @param input
     *            The password
     * @return The password hash in format pbkdf2$&lt;hex-salt&gt;$&lt;hex-hash&gt;
     */
    public static String passwordHash(String input) {
        return passwordHashPBKDF2(input);
    }

    /**
     * Create a password hash using PBKDF2WithHmacSHA256 with a random salt.
     *
     * @param password
     *            The password to hash
     * @return A string in format: pbkdf2$&lt;hex-salt&gt;$&lt;hex-hash&gt;
     */
    public static String passwordHashPBKDF2(String password) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            random.nextBytes(salt);

            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 260000, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();

            return "pbkdf2$" + bytesToHex(salt) + "$" + bytesToHex(hash);
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    /**
     * Verify a password against a stored PBKDF2 hash produced by {@link #passwordHashPBKDF2(String)}.
     *
     * @param password
     *            The password to check
     * @param stored
     *            The stored hash string in format pbkdf2$&lt;hex-salt&gt;$&lt;hex-hash&gt;
     * @return true if the password matches
     */
    public static boolean checkPasswordPBKDF2(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 3 || !parts[0].equals("pbkdf2")) {
                return false;
            }
            byte[] salt = hexToBytes(parts[1]);
            byte[] expectedHash = hexToBytes(parts[2]);

            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 260000, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actualHash = factory.generateSecret(spec).getEncoded();

            return Arrays.equals(expectedHash, actualHash);
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    /**
     * Encrypt a String with AES/GCM/NoPadding using the application secret
     *
     * @param value
     *            The String to encrypt
     * @return A Base64-encoded string (IV prepended to ciphertext)
     */
    public static String encryptAES(String value) {
        try {
            byte[] keyBytes = deriveAesKey();
            SecretKeySpec skeySpec = new SecretKeySpec(keyBytes, "AES");

            SecureRandom random = new SecureRandom();
            byte[] iv = new byte[12];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return new String(Base64.encodeBase64(combined), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    /**
     * Encrypt a String with the AES encryption standard. Private key must have a length of 16 bytes.
     *
     * @deprecated This variant resolves to {@code AES/ECB/PKCS5Padding}, which is deterministic
     *             and unsafe for any payload longer than one block. Use {@link #encryptAES(String)}
     *             which uses {@code AES/GCM/NoPadding} with a random IV. Will be removed in a
     *             future release.
     *
     * @param value
     *            The String to encrypt
     * @param privateKey
     *            The key used to encrypt
     * @return An hexadecimal encrypted string
     */
    @Deprecated
    public static String encryptAES(String value, String privateKey) {
        try {
            byte[] raw = privateKey.getBytes(UTF_8);
            SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
            return Codec.byteToHexString(cipher.doFinal(value.getBytes(UTF_8)));
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    /**
     * Decrypt a String with AES/GCM/NoPadding using the application secret
     *
     * @param value
     *            A Base64-encoded string (IV prepended to ciphertext)
     * @return The decrypted String
     */
    public static String decryptAES(String value) {
        try {
            byte[] keyBytes = deriveAesKey();
            SecretKeySpec skeySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] combined = Base64.decodeBase64(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = Arrays.copyOfRange(combined, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(combined, 12, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw new UnexpectedException(new Exception("Decryption failed", ex));
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    /**
     * Decrypt a String with the AES encryption standard. Private key must have a length of 16 bytes.
     *
     * @deprecated Companion to the deprecated {@link #encryptAES(String, String)}. Use
     *             {@link #decryptAES(String)} for ciphertexts produced by the GCM variant.
     *
     * @param value
     *            An hexadecimal encrypted string
     * @param privateKey
     *            The key used to encrypt
     * @return The decrypted String
     */
    @Deprecated
    public static String decryptAES(String value, String privateKey) {
        try {
            byte[] raw = privateKey.getBytes(UTF_8);
            SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec);
            return new String(cipher.doFinal(Codec.hexStringToByte(value)), UTF_8);
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int bite = bytes[i] & 0xff;
            hexChars[i * 2]     = HEX_CHARS[bite >> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[bite & 0xf];
        }
        return new String(hexChars);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Derive a 16-byte AES key from {@link Play#secretKey} via HKDF-SHA256 (RFC 5869).
     * <p>
     * The previous implementation truncated/zero-padded the raw UTF-8 bytes of the
     * secret to exactly 16 bytes — short secrets ended up zero-padded into the key
     * (predictable bytes), and entropy in long secrets past byte 16 was discarded.
     * HKDF takes any-length input keying material and produces a uniformly
     * distributed key. The {@code info} string scopes this derivation to AES so
     * the same secret can safely be used for HMAC signing without one weakness
     * leaking into the other.
     * <p>
     * <b>Compatibility note:</b> Ciphertext produced by older versions (using the
     * truncate/pad derivation) is not decryptable with this key. Apps that store
     * AES-encrypted data long-term must re-encrypt during the upgrade window.
     */
    private static byte[] deriveAesKey() {
        return hkdfSha256(Play.secretKey.getBytes(UTF_8),
                          null,
                          "play.encrypt.aes.v1".getBytes(UTF_8),
                          16);
    }

    /**
     * RFC 5869 HKDF with HMAC-SHA256 (extract-then-expand).
     *
     * @param ikm   input keying material (the long-term secret)
     * @param salt  optional salt; null is treated as a zero-filled 32-byte salt
     * @param info  context/application-specific info string for domain separation
     * @param length  desired output length in bytes (max 32 * 255 = 8160)
     */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length < 0 || length > 32 * 255) {
            throw new IllegalArgumentException("HKDF length out of range: " + length);
        }
        if (info == null) info = new byte[0];
        if (salt == null) salt = new byte[32];
        try {
            // Extract: PRK = HMAC-SHA256(salt, IKM)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);

            // Expand: T(0)=empty, T(i)=HMAC(PRK, T(i-1) | info | i); OKM = T(1)|T(2)|...
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] okm = new byte[length];
            byte[] t = new byte[0];
            int written = 0;
            for (int counter = 1; written < length; counter++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) counter);
                t = mac.doFinal();
                int copy = Math.min(t.length, length - written);
                System.arraycopy(t, 0, okm, written, copy);
                written += copy;
            }
            return okm;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new UnexpectedException(e);
        }
    }
}
