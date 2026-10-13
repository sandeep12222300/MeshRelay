package io.meshrelay.prototype.controller;

import io.meshrelay.prototype.model.DemoUser;
import io.meshrelay.prototype.model.DemoUserRepository;
import io.meshrelay.prototype.service.AuthenticationService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes registration, login, current-session, and logout endpoints for the local prototype.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String USER_ID = "meshrelay.userId";
    private final AuthenticationService authentication;
    private final DemoUserRepository users;

    public AuthController(AuthenticationService authentication, DemoUserRepository users) {
        this.authentication = authentication;
        this.users = users;
    }

    /**
     * Creates a demo user and starts a session; the opening balance is fictional.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegistrationRequest request, HttpSession session) {
        DemoUser user = authentication.register(request.fullName, request.username, request.mobileNumber,
                request.email, request.vpa, request.password);
        session.setAttribute(USER_ID, user.getId());
        return userResponse(user);
    }

    /**
     * Verifies a demo user's credentials and starts a session.
     */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        DemoUser user = authentication.authenticate(request.identifier, request.password);
        session.setAttribute(USER_ID, user.getId());
        return userResponse(user);
    }

    /**
     * Returns the signed-in demo user's public profile, or an anonymous response.
     */
    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Object id = session.getAttribute(USER_ID);
        if (!(id instanceof Long userId)) return Map.of("authenticated", false);
        return users.findById(userId).map(user -> {
            Map<String, Object> result = userResponse(user);
            result.put("authenticated", true);
            return result;
        }).orElseGet(() -> Map.of("authenticated", false));
    }

    /**
     * Ends the current browser session without changing ledger history.
     */
    @PostMapping("/logout")
    public Map<String, String> logout(HttpSession session) {
        session.invalidate();
        return Map.of("message", "Signed out of the demo session.");
    }

    private Map<String, Object> userResponse(DemoUser user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticated", true);
        response.put("fullName", user.getFullName());
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("vpa", user.getVpa());
        response.put("mobileNumber", user.getMobileNumber());
        return response;
    }

    /**
     * Validated fields required by the registration form.
     */
    public static class RegistrationRequest {
        @NotBlank
        @Size(max = 120)
        public String fullName;
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._-]{3,40}")
        public String username;
        @NotBlank
        @Pattern(regexp = "[0-9+() -]{8,20}")
        public String mobileNumber;
        @NotBlank
        @Email
        @Size(max = 160)
        public String email;
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._-]{3,100}@[A-Za-z0-9.-]{2,60}")
        public String vpa;
        @NotBlank
        @Size(min = 8, max = 128)
        public String password;
    }

    /**
     * Validated credentials for username-or-email login.
     */
    public static class LoginRequest {
        @NotBlank
        @Size(max = 160)
        public String identifier;
        @NotBlank
        @Size(min = 8, max = 128)
        public String password;
    }
}
