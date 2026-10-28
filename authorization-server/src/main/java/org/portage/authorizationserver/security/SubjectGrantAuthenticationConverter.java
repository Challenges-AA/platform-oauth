package org.portage.authorizationserver.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Converts the private form grant to an Authentication consumed by Spring's token endpoint.
 */
public final class SubjectGrantAuthenticationConverter implements AuthenticationConverter {
    @Override
    public Authentication convert(HttpServletRequest request) {
        // Chaque paramètre de cette extension doit être unique afin d'éviter toute ambiguïté.
        String grant = one(request, "grant_type");
        if (!SubjectGrantAuthenticationToken.GRANT_TYPE.getValue().equals(grant)) return null;
        var params = new LinkedHashMap<String, Object>();
        for (String name : List.of("username", "password", "subject", "scope")) {
            String value = one(request, name);
            if (!StringUtils.hasText(value)) throw invalidRequest();
            params.put(name, name.equals("scope") ? Set.copyOf(Arrays.asList(value.trim().split("\\s+"))) : value);
        }
        return new SubjectGrantAuthenticationToken(SecurityContextHolder.getContext().getAuthentication(), params);
    }

    private static String one(HttpServletRequest request, String key) {
        String[] values = request.getParameterValues(key);
        if (values == null || values.length != 1) throw invalidRequest();
        return values[0];
    }

    private static OAuth2AuthenticationException invalidRequest() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_request"));
    }
}
