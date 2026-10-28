package org.portage.authorizationserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRegistryTest {
    /** Les mots de passe configurés sont distincts et les références non résolues échouent. */
    @Test
    void rejectsRepeatedPasswordOrMissingPassword() {
        for (String invalid : new String[]{"same-password", "", "${MISSING_PASSWORD}"}) {
            var users = new java.util.ArrayList<UserProperties.User>();
            for (int i = 0; i < 5; i++)
                users.add(new UserProperties.User("u" + i,
                        i < 2 ? invalid : "unique-" + i, "s" + i));
            assertThatThrownBy(() -> UserRegistry.from(users, new BCryptPasswordEncoder()))
                    .isInstanceOf(org.portage.authorizationserver.error.InvalidIdentityConfigurationException.class);
        }
    }

    /** Vérifie le chargement, l'unicité des identités et le hachage des mots de passe. */
    @Test
    void loadsFiveDistinctUsersWithEncodedPasswordsAndStableSubjects() {
        var registry = UserRegistry.from(List.of(
                new UserProperties.User("user1", "plain1", "subject-1"),
                new UserProperties.User("user2", "plain2", "subject-2"),
                new UserProperties.User("user3", "plain3", "subject-3"),
                new UserProperties.User("user4", "plain4", "subject-4"),
                new UserProperties.User("user5", "plain5", "subject-5")), new BCryptPasswordEncoder());

        assertThat(registry.users()).hasSize(5);
        assertThat(registry.users()).extracting(UserRegistry.User::username).doesNotHaveDuplicates();
        assertThat(registry.users()).extracting(UserRegistry.User::subject).containsExactlyInAnyOrder(
                "subject-1", "subject-2", "subject-3", "subject-4", "subject-5");
        assertThat(registry.authenticate("user1", "plain1")).contains("subject-1");
        assertThat(registry.users().getFirst().passwordHash()).doesNotContain("plain1");
    }

    /** Le registre refuse les listes de mauvaise taille et les noms/sujets dupliqués. */
    @Test
    void rejectsDuplicateOrInsufficientUserConfiguration() {
        var encoder = new BCryptPasswordEncoder();
        assertThatThrownBy(() -> UserRegistry.from(List.of(), encoder)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserRegistry.from(List.of(
                new UserProperties.User("same", "x", "s1"),
                new UserProperties.User("same", "y", "s2"),
                new UserProperties.User("u3", "z", "s3"),
                new UserProperties.User("u4", "z", "s4"),
                new UserProperties.User("u5", "z", "s5")), encoder)).isInstanceOf(IllegalArgumentException.class);
    }
}

