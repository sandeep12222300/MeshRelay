package io.meshrelay.prototype.service;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Hashes prototype account passwords with PBKDF2-HMAC-SHA256 and a unique random salt.
 */
@Service
public class PasswordHashService {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH = 256;
    private final SecureRandom random = new SecureRandom();

    /**
     * Returns an encoded salt-and-hash string suitable for database storage.
     */
    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] derived = derive(password.toCharArray(), salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * Checks a password using a constant-time comparison of the derived hash.
     */
    public boolean matches(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password.toCharArray(), salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("Password hashing is unavailable", ex);
        } finally {
            spec.clearPassword();
        }
    }
}
