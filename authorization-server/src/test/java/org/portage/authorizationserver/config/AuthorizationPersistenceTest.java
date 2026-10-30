package org.portage.authorizationserver.config;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.portage.authorizationserver.security.RotatingAuthorizationService;
import static org.assertj.core.api.Assertions.*;

class AuthorizationPersistenceTest {
    @TempDir Path directory;

    @Test
    void reopensEncryptedAuthorizationsConsentsAndAuthenticatedSessions() throws Exception {
        var config = new AuthorizationPersistenceConfiguration();
        String key = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
        var client = RegisteredClient.withId("persistent-client").clientId("public")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:8080/callback").scope("hello.read").build();
        var clients = new InMemoryRegisteredClientRepository(client);
        String sessionId;
        var principal = UsernamePasswordAuthenticationToken.authenticated(
                User.withUsername("subject-1").password("").roles("USER").build(), null, List.of());
        try (var source = config.dataSource(directory.toString(), key)) {
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            var jdbc = new JdbcTemplate(source);
            var authorizations = new RotatingAuthorizationService(jdbc, clients, transactions);
            authorizations.save(OAuth2Authorization.withRegisteredClient(client).id("family")
                    .principalName("subject-1").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizedScopes(Set.of("hello.read")).attribute(java.security.Principal.class.getName(), principal)
                    .refreshToken(new OAuth2RefreshToken("opaque-test-refresh", Instant.now(), Instant.now().plusSeconds(300))).build());
            config.authorizationConsentService(source, clients).save(OAuth2AuthorizationConsent.withId(client.getId(), "subject-1")
                    .scope("hello.read").build());
            @SuppressWarnings({"rawtypes", "unchecked"})
            org.springframework.session.SessionRepository<org.springframework.session.Session> sessions =
                    (org.springframework.session.SessionRepository) new JdbcIndexedSessionRepository(jdbc, transactions);
            var session = sessions.createSession();
            session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(principal));
            sessions.save(session);
            sessionId = session.getId();
        }
        byte[] bytes = Files.readAllBytes(directory.resolve("authorization.mv.db"));
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("opaque-test-refresh", "subject-1", "SPRING_SECURITY_CONTEXT");
        try (var source = config.dataSource(directory.toString(), key)) {
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            var jdbc = new JdbcTemplate(source);
            var restored = new RotatingAuthorizationService(jdbc, clients, transactions);
            var authorization = restored.findByToken("opaque-test-refresh", OAuth2TokenType.REFRESH_TOKEN);
            assertThat(authorization.getPrincipalName()).isEqualTo("subject-1");
            assertThat(authorization.getAuthorizedScopes()).containsExactly("hello.read");
            assertThat(config.authorizationConsentService(source, clients).findById(client.getId(), "subject-1")).isNotNull();
            @SuppressWarnings({"rawtypes", "unchecked"})
            org.springframework.session.SessionRepository<org.springframework.session.Session> sessions =
                    (org.springframework.session.SessionRepository) new JdbcIndexedSessionRepository(jdbc, transactions);
            var session = sessions.findById(sessionId);
            assertThat(session).isNotNull();
            SecurityContextImpl context = session.getAttribute("SPRING_SECURITY_CONTEXT");
            assertThat(context.getAuthentication().getName()).isEqualTo("subject-1");
        }
        String wrong = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
        assertThatThrownBy(() -> {
            try (var ignored = config.dataSource(directory.toString(), wrong)) { }
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesMissingOrWeakStorageKeys() {
        var config = new AuthorizationPersistenceConfiguration();
        for (String key : List.of("", "${OAUTH_STORAGE_KEY}", Base64.getEncoder().encodeToString(new byte[16])))
            assertThatThrownBy(() -> config.dataSource(directory.toString(), key)).isInstanceOf(IllegalArgumentException.class);
    }
}
