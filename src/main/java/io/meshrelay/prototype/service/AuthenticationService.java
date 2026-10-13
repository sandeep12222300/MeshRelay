package io.meshrelay.prototype.service;

import io.meshrelay.prototype.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Implements demo registration and login while keeping credential handling out of controllers.
 */
@Service
public class AuthenticationService {
    private final DemoUserRepository users;
    private final AccountRepository accounts;
    private final PasswordHashService passwords;

    public AuthenticationService(DemoUserRepository users, AccountRepository accounts, PasswordHashService passwords) {
        this.users = users;
        this.accounts = accounts;
        this.passwords = passwords;
    }

    /**
     * Registers a prototype user and creates a fictional demo-ledger account with an initial balance.
     */
    @Transactional
    public DemoUser register(String fullName, String username, String mobile, String email, String vpa, String password) {
        String normalizedUsername = username.trim();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String normalizedVpa = vpa.trim().toLowerCase(Locale.ROOT);
        if (users.existsByUsernameIgnoreCase(normalizedUsername))
            throw new IllegalArgumentException("Username is already registered.");
        if (users.existsByEmailIgnoreCase(normalizedEmail))
            throw new IllegalArgumentException("Email is already registered.");
        if (users.existsByMobileNumber(mobile.trim()))
            throw new IllegalArgumentException("Mobile number is already registered.");
        if (users.existsByVpaIgnoreCase(normalizedVpa) || accounts.existsById(normalizedVpa))
            throw new IllegalArgumentException("UPI ID is already in use.");
        DemoUser user = users.save(new DemoUser(fullName.trim(), normalizedUsername, mobile.trim(), normalizedEmail,
                normalizedVpa, passwords.hash(password)));
        accounts.save(new Account(normalizedVpa, fullName.trim(), new BigDecimal("5000.00")));
        return user;
    }

    /**
     * Authenticates by username or email and returns no user when the password is incorrect.
     */
    @Transactional(readOnly = true)
    public DemoUser authenticate(String identifier, String password) {
        DemoUser user = users.findByUsernameIgnoreCaseOrEmailIgnoreCase(identifier.trim(), identifier.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username/email or password."));
        if (!passwords.matches(password, user.getPasswordHash()))
            throw new IllegalArgumentException("Invalid username/email or password.");
        return user;
    }
}
