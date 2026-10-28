package org.portage.resourceserver.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Application claims are mandatory in addition to Nimbus signature and timestamp validation.
 */
final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;

    AccessTokenClaimsValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object keyId = jwt.getHeaders().get("kid");
        if (jwt.getExpiresAt() == null || jwt.getNotBefore() == null || jwt.getIssuedAt() == null
                || jwt.getSubject() == null || jwt.getSubject().isBlank()
                || jwt.getId() == null || jwt.getId().isBlank()
                || keyId == null || keyId.toString().isBlank()
                || jwt.getAudience() == null || !jwt.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
