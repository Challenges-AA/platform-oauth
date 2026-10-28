package org.portage.authorizationserver.config;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;

/**
 * Immutable in-memory identity registry; configured passwords are encoded at startup.
 */
public final class UserRegistry {
    public record User(String username, String passwordHash, String subject) {
    }

    private final Map<String, User> byUsername;
    private final PasswordEncoder encoder;

    private UserRegistry(Map<String, User> users, PasswordEncoder encoder) {
        this.byUsername = Map.copyOf(users);
        this.encoder = encoder;
    }

    public static UserRegistry from(List<UserProperties.User> configured, PasswordEncoder encoder) {
        if (configured == null || configured.size() != 5)
            throw new org.portage.authorizationserver.error.InvalidIdentityConfigurationException("Exactly five users are required");
        var users = new LinkedHashMap<String, User>();
        var subjects = new HashSet<String>();
        var passwords = new HashSet<String>();
        for (var item : configured) {
            if (item == null || blank(item.username()) || blank(item.password()) || item.password().startsWith("${") || blank(item.subject()))
                throw new org.portage.authorizationserver.error.InvalidIdentityConfigurationException("User fields are required");
            if (!passwords.add(item.password()))
                throw new org.portage.authorizationserver.error.InvalidIdentityConfigurationException("User passwords must be distinct");
            var user = new User(item.username(), encoder.encode(item.password()), item.subject());
            if (users.putIfAbsent(user.username(), user) != null || !subjects.add(user.subject()))
                throw new org.portage.authorizationserver.error.InvalidIdentityConfigurationException("Usernames and subjects must be unique");
        }
        return new UserRegistry(users, encoder);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public List<User> users() {
        return List.copyOf(byUsername.values());
    }

    public Optional<String> authenticate(String username, String password) {
        var user = byUsername.get(username);
        return user != null && password != null && encoder.matches(password, user.passwordHash()) ? Optional.of(user.subject()) : Optional.empty();
    }

    public Optional<User> find(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }
}

