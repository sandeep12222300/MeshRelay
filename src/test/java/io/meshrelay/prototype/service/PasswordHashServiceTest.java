package io.meshrelay.prototype.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that demo passwords are salted, verifiable, and never stored as plaintext.
 */
class PasswordHashServiceTest {
    private final PasswordHashService passwords = new PasswordHashService();

    /**
     * A correct password matches its hash while a different password does not.
     */
    @Test
    void verifiesPasswordAndRejectsIncorrectPassword() {
        String encoded = passwords.hash("prototype-password-123");
        assertNotEquals("prototype-password-123", encoded);
        assertTrue(passwords.matches("prototype-password-123", encoded));
        assertFalse(passwords.matches("wrong-password", encoded));
    }

    /**
     * Independent hashes use independent salts so equal passwords do not share stored hashes.
     */
    @Test
    void generatesDifferentHashesForSamePassword() {
        assertNotEquals(passwords.hash("prototype-password-123"), passwords.hash("prototype-password-123"));
    }
}
