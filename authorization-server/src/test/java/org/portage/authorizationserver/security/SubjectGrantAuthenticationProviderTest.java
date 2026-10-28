package org.portage.authorizationserver.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.portage.authorizationserver.config.UserProperties;
import org.portage.authorizationserver.config.UserRegistry;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubjectGrantAuthenticationProviderTest {
    private SubjectGrantAuthenticationProvider provider;
    private RegisteredClient client;
    private OAuth2ClientAuthenticationToken clientPrincipal;

    @BeforeEach
    /** Prépare un client autorisé, cinq utilisateurs et un encodeur qui échoue si un refus signe. */
    void setup() {
        var users = UserRegistry.from(List.of(
                new UserProperties.User("user1", "secret", "subject-1"),
                new UserProperties.User("user2", "secret2", "subject-2"),
                new UserProperties.User("user3", "secret3", "subject-3"),
                new UserProperties.User("user4", "secret4", "subject-4"),
                new UserProperties.User("user5", "secret5", "subject-5")), new BCryptPasswordEncoder());
        provider = new SubjectGrantAuthenticationProvider(users, params -> {
            throw new AssertionError("Rejected requests must never reach signing");
        });
        client = RegisteredClient.withId("client-id").clientId("demo-client").clientSecret("encoded")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(SubjectGrantAuthenticationToken.GRANT_TYPE).scope("hello.read").build();
        clientPrincipal = new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, null);
    }

    @Test
    /** Le provider annonce qu'il sait traiter le type d'authentification du grant privé. */
    void supportsPrivateGrant() {
        assertThat(provider.supports(SubjectGrantAuthenticationToken.class)).isTrue();
    }

    @Test
    /** Un mot de passe incorrect ne doit pas parvenir à l'étape de signature. */
    void rejectsWrongPasswordAsInvalidGrant() {
        assertError(Map.of("username", "user1", "password", "wrong", "subject", "subject-1", "scope", Set.of("hello.read")), "invalid_grant");
    }

    @Test
    /** Un client ne peut pas choisir le sujet d'un autre utilisateur. */
    void rejectsSubjectThatDoesNotBelongToAuthenticatedUser() {
        assertError(Map.of("username", "user1", "password", "secret", "subject", "subject-2", "scope", Set.of("hello.read")), "invalid_grant");
    }

    @Test
    /** Le scope est borné au droit applicatif explicitement pris en charge. */
    void rejectsUnapprovedScope() {
        assertError(Map.of("username", "user1", "password", "secret", "subject", "subject-1", "scope", Set.of("admin")), "invalid_scope");
    }

    private void assertError(Map<String, Object> params, String code) {
        var request = new SubjectGrantAuthenticationToken(clientPrincipal, params);
        assertThatThrownBy(() -> provider.authenticate(request)).isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode()).isEqualTo(code);
    }
}
