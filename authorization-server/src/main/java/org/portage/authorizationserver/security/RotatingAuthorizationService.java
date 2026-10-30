package org.portage.authorizationserver.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring authorization storage plus durable refresh-token family history.
 * Token endpoint operations must hold the database lifecycle lock from lookup through save.
 * Old refresh values are retained only as SHA-256 hashes; replay revokes the whole grant.
 */
public final class RotatingAuthorizationService implements OAuth2AuthorizationService {
    private final JdbcOAuth2AuthorizationService delegate;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public RotatingAuthorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients, TransactionTemplate transactions) {
        this.delegate = new JdbcOAuth2AuthorizationService(jdbc, clients);
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public void lock() {
        jdbc.queryForObject("SELECT id FROM oauth_lifecycle_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        if (authorization.getRefreshToken() != null && authorization.getAttribute(RotatingRefreshTokenGenerator.FAMILY_EXPIRES_AT) == null)
            authorization = OAuth2Authorization.from(authorization).attribute(RotatingRefreshTokenGenerator.FAMILY_EXPIRES_AT,
                    authorization.getRefreshToken().getToken().getIssuedAt().plus(java.time.Duration.ofDays(7))).build();
        final OAuth2Authorization saved = authorization;
        transactions.executeWithoutResult(status -> {
            lock();
            delegate.save(saved);
            var refresh = saved.getRefreshToken();
            if (refresh != null) {
                String hash = hash(refresh.getToken().getTokenValue());
                if (jdbc.queryForObject("SELECT COUNT(*) FROM oauth_refresh_history WHERE token_hash=?", Integer.class, hash) == 0)
                    jdbc.update("INSERT INTO oauth_refresh_history VALUES (?, ?, ?, ?)", hash, saved.getId(),
                            saved.getRegisteredClientId(), Timestamp.from(refresh.getToken().getExpiresAt()));
            }
        });
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        transactions.executeWithoutResult(status -> {
            lock();
            delegate.remove(authorization);
            // Keep hashes until the family would expire, so revoked tokens never become valid again.
        });
    }

    @Override
    public OAuth2Authorization findById(String id) { return delegate.findById(id); }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        return transactions.execute(status -> {
            lock();
            return lookup(token, type);
        });
    }

    private OAuth2Authorization lookup(String token, OAuth2TokenType type) {
        var current = delegate.findByToken(token, type);
        if (current != null) return current;
        if (type != null && !OAuth2TokenType.REFRESH_TOKEN.equals(type)) return null;
        // Only a request identifying the original client may revoke a family on replay.
        // A token from a different client must fail without destroying its owner's session.
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2ClientAuthenticationToken client)
                || client.getRegisteredClient() == null) return null;
        var families = jdbc.queryForList("SELECT authorization_id FROM oauth_refresh_history WHERE token_hash=? AND client_id=?",
                String.class, hash(token), client.getRegisteredClient().getId());
        for (String id : families) {
            var family = delegate.findById(id);
            if (family != null) {
                var builder = OAuth2Authorization.from(family);
                if (family.getRefreshToken() != null) builder.invalidate(family.getRefreshToken().getToken());
                if (family.getAccessToken() != null) builder.invalidate(family.getAccessToken().getToken());
                delegate.save(builder.build());
            }
        }
        return null;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
