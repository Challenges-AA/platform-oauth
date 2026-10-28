package org.portage.resourceserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenClaimsValidatorTest {
    private final AccessTokenClaimsValidator policy = new AccessTokenClaimsValidator("portage-api");

    /** Construit un JWT simulé avec tous les champs obligatoires attendus par l'API. */
    private Jwt.Builder valid() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("unit-test-only").header("alg", "ES256").header("kid", "active")
                .subject("subject-1").audience(List.of("portage-api")).claim("jti", "test-id")
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(300));
    }

    @Test
    /** Un jeton complet destiné à la bonne audience respecte la politique métier. */
    void acceptsCompleteApplicationClaims() {
        assertThat(policy.validate(valid().build()).hasErrors()).isFalse();
    }

    @Test
    /** Chaque claim obligatoire, ainsi que l'identifiant de clé kid, est requis. */
    void refusesEachMissingMandatoryClaim() {
        for (String claim : List.of("exp", "nbf", "iat", "sub", "aud", "jti")) {
            var builder = valid().claims(claims -> claims.remove(claim));
            assertThat(policy.validate(builder.build()).hasErrors()).as("missing %s", claim).isTrue();
        }
        assertThat(policy.validate(valid().headers(headers -> headers.remove("kid")).build()).hasErrors()).isTrue();
    }

    @Test
    /** Les identifiants non vides et l'audience configurée sont exigés. */
    void refusesEmptyIdentifiersAndOtherAudience() {
        assertThat(policy.validate(valid().subject(" ").build()).hasErrors()).isTrue();
        assertThat(policy.validate(valid().claim("jti", " ").build()).hasErrors()).isTrue();
        assertThat(policy.validate(valid().header("kid", " ").build()).hasErrors()).isTrue();
        assertThat(policy.validate(valid().audience(List.of("another-api")).build()).hasErrors()).isTrue();
    }
}
