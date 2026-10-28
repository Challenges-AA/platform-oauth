package org.portage.authorizationserver.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

import java.util.Map;

public final class SubjectGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {
    public static final AuthorizationGrantType GRANT_TYPE = new AuthorizationGrantType("urn:portage:params:oauth:grant-type:subject");

    public SubjectGrantAuthenticationToken(Authentication clientPrincipal, Map<String, Object> parameters) {
        super(GRANT_TYPE, clientPrincipal, parameters);
    }
}
