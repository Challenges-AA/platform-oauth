package org.portage.authorizationserver.security;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.*;

/** Opt-in public-client issuance with rotation enforced by the authorization store.
 * Idle expiry is client-configured; an entire family also has an absolute seven-day lifetime.
 */
public final class RotatingRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
    public static final String FAMILY_EXPIRES_AT = "portage.family.expires-at";
    private final Base64StringKeyGenerator random = new Base64StringKeyGenerator(java.util.Base64.getUrlEncoder().withoutPadding(), 64);
    private final Clock clock;
    public RotatingRefreshTokenGenerator(Clock clock) { this.clock = clock; }

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
        Instant now = clock.instant();
        Instant absolute = context.getAuthorization() == null ? null
                : context.getAuthorization().getAttribute(FAMILY_EXPIRES_AT);
        if (absolute != null && !absolute.isAfter(now)) throw new OAuth2AuthenticationException("invalid_grant");
        if (absolute == null) absolute = now.plus(Duration.ofDays(7));
        Instant expires = now.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        if (expires.isAfter(absolute)) expires = absolute;
        return new OAuth2RefreshToken(random.generateKey(), now, expires);
    }
}
