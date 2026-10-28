package org.portage.authorizationserver.security;

import org.portage.authorizationserver.config.UserRegistry;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates grant, authenticated client, scope and user before creating a short-lived signed JWT.
 */
public final class SubjectGrantAuthenticationProvider implements AuthenticationProvider {
    private final UserRegistry users;
    private final JwtEncoder encoder;
    private final String issuer;
    private final Clock clock;

    public SubjectGrantAuthenticationProvider(UserRegistry users, JwtEncoder encoder) {
        this(users, encoder, "http://127.0.0.1:9000", Clock.systemUTC());
    }

    public SubjectGrantAuthenticationProvider(UserRegistry users, JwtEncoder encoder, String issuer, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.issuer = issuer;
        this.clock = clock;
    }

    @Override
    public boolean supports(Class<?> type) {
        return SubjectGrantAuthenticationToken.class.isAssignableFrom(type);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var request = (SubjectGrantAuthenticationToken) authentication;
        if (!(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientAuth) || !clientAuth.isAuthenticated())
            throw error("invalid_client");
        RegisteredClient client = clientAuth.getRegisteredClient();
        if (client == null) throw error("invalid_client");
        if (!client.getAuthorizationGrantTypes().contains(SubjectGrantAuthenticationToken.GRANT_TYPE))
            throw error("unauthorized_client");
        @SuppressWarnings("unchecked") Set<String> scopes = (Set<String>) request.getAdditionalParameters().get("scope");
        if (scopes == null || scopes.isEmpty() || !client.getScopes().containsAll(scopes) || !scopes.equals(Set.of("hello.read")))
            throw error("invalid_scope");
        String username = (String) request.getAdditionalParameters().get("username");
        String password = (String) request.getAdditionalParameters().get("password");
        String submittedSubject = (String) request.getAdditionalParameters().get("subject");
        var actualSubject = users.authenticate(username, password);
        if (actualSubject.isEmpty() || !actualSubject.get().equals(submittedSubject)) throw error("invalid_grant");
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(actualSubject.get()).audience(List.of("portage-api"))
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(300)).id(UUID.randomUUID().toString())
                .claim("scope", "hello.read").build();
        Jwt jwt;
        try {
            jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256)
                    .type("JWT").build(), claims));
        } catch (JwtEncodingException e) {
            throw error("temporarily_unavailable");
        }
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, jwt.getTokenValue(), now, now.plusSeconds(300), scopes);
        return new OAuth2AccessTokenAuthenticationToken(client, clientAuth, token);
    }

    private static OAuth2AuthenticationException error(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code));
    }
}
