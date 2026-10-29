package org.portage.authorizationserver.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** Public clients identify themselves; possession of a bound refresh token is the credential.
 * This deliberately does not handle authorization-code exchange (Spring validates PKCE there).
 */
public final class PublicRefreshClientAuthentication implements AuthenticationConverter, AuthenticationProvider {
    private final RegisteredClientRepository clients;
    public PublicRefreshClientAuthentication(RegisteredClientRepository clients) { this.clients = clients; }

    @Override
    public Authentication convert(HttpServletRequest request) {
        boolean refresh = "/oauth2/token".equals(request.getServletPath())
                && "refresh_token".equals(request.getParameter("grant_type"));
        boolean revoke = "/oauth2/revoke".equals(request.getServletPath());
        if (!refresh && !revoke) return null;
        if (request.getHeader("Authorization") != null || request.getParameter("client_secret") != null) return null;
        String id = request.getParameter("client_id");
        if (id == null || id.isBlank() || request.getParameterValues("client_id").length != 1) throw invalidClient();
        return new OAuth2ClientAuthenticationToken(id, ClientAuthenticationMethod.NONE, null, Map.of("portage.public-refresh", true));
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var request = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(request.getClientAuthenticationMethod())) return null;
        // Leave the code_verifier flow to Spring's PublicClientAuthenticationProvider.
        // HTTP parameters are strings: a caller cannot forge this internal Boolean marker.
        if (!Boolean.TRUE.equals(request.getAdditionalParameters().get("portage.public-refresh"))) return null;
        var client = clients.findByClientId(request.getPrincipal().toString());
        if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                || !client.getClientSettings().isRequireProofKey()
                || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) throw invalidClient();
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static OAuth2AuthenticationException invalidClient() { return new OAuth2AuthenticationException("invalid_client"); }
}
