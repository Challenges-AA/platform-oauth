package org.portage.resourceserver.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.time.Duration;

@Configuration
public class SecurityConfiguration {
    @Bean
    NimbusJwtDecoder jwtDecoder(@Value("${resource-server.jwks-uri}") String jwksUri,
                                @Value("${resource-server.issuer}") String issuer,
                                @Value("${resource-server.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256).build();
        OAuth2TokenValidator<Jwt> standard = new JwtIssuerValidator(issuer);
        OAuth2TokenValidator<Jwt> timestamp = new JwtTimestampValidator(Duration.ZERO);
        OAuth2TokenValidator<Jwt> requiredClaims = new AccessTokenClaimsValidator(audience);
        decoder.setJwtValidator(jwt -> {
            OAuth2TokenValidatorResult result = timestamp.validate(jwt);
            if (result.hasErrors()) return result;
            result = standard.validate(jwt);
            return result.hasErrors() ? result : requiredClaims.validate(jwt);
        });
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("scope");
        authorities.setAuthorityPrefix("SCOPE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    SecurityFilterChain resourceSecurity(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(jsonUnauthorized()).accessDeniedHandler(jsonForbidden()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/hello").hasAuthority("SCOPE_hello.read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(jsonUnauthorized()).accessDeniedHandler(jsonForbidden()));
        return http.build();
    }

    private AuthenticationEntryPoint jsonUnauthorized() {
        return (request, response, exception) -> writeError(response, HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    private AccessDeniedHandler jsonForbidden() {
        return (request, response, exception) -> writeError(response, HttpStatus.FORBIDDEN, "forbidden");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String error) throws java.io.IOException {
        response.setStatus(status.value());
        response.setHeader("WWW-Authenticate", status == HttpStatus.UNAUTHORIZED ? "Bearer" : "Bearer error=\"insufficient_scope\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
