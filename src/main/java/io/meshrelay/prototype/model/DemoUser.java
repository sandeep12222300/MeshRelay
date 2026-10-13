package io.meshrelay.prototype.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Stores a registered prototype user. Passwords are stored only as salted PBKDF2 hashes.
 */
@Entity
@Table(name = "demo_users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_demo_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_demo_users_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_demo_users_mobile", columnNames = "mobile_number"),
        @UniqueConstraint(name = "uk_demo_users_vpa", columnNames = "vpa")
})
public class DemoUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String fullName;
    @Column(nullable = false, length = 40)
    private String username;
    @Column(name = "mobile_number", nullable = false, length = 20)
    private String mobileNumber;
    @Column(nullable = false, length = 160)
    private String email;
    @Column(nullable = false, length = 120)
    private String vpa;
    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;
    @Column(nullable = false)
    private Instant createdAt;

    protected DemoUser() {
    }

    public DemoUser(String fullName, String username, String mobileNumber, String email, String vpa, String passwordHash) {
        this.fullName = fullName;
        this.username = username;
        this.mobileNumber = mobileNumber;
        this.email = email;
        this.vpa = vpa;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getUsername() {
        return username;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public String getEmail() {
        return email;
    }

    public String getVpa() {
        return vpa;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
