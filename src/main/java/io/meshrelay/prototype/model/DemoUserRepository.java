package io.meshrelay.prototype.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Provides lookups for demo users during registration and login.
 */
public interface DemoUserRepository extends JpaRepository<DemoUser, Long> {
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByMobileNumber(String mobileNumber);

    boolean existsByVpaIgnoreCase(String vpa);

    Optional<DemoUser> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);
}
