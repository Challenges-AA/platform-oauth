package org.portage.authorizationserver.config;

import org.portage.authorizationserver.security.SubjectGrantAuthenticationConverter;
import org.portage.authorizationserver.security.SubjectGrantAuthenticationProvider;
import org.portage.authorizationserver.security.SubjectGrantAuthenticationToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

@Configuration
public class AuthorizationSecurityConfiguration {
    /**
     * Publie le JWKS sans authentification. Cette chaîne, prioritaire, ne
     * laisse passer que GET /oauth2/jwks ; les autres requêtes sont refusées.
     */
    @Bean
    @Order(0)
    SecurityFilterChain publicKeysChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/oauth2/jwks").authorizeHttpRequests(a -> a
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/oauth2/jwks").permitAll()
                .anyRequest().denyAll()).build();
    }

    /**
     * Déclare le client OAuth de démonstration en mémoire, son mode
     * d'authentification, le grant personnalisé et le scope autorisé.
     */
    @Bean
    RegisteredClientRepository registeredClientRepository(PasswordEncoder encoder, @Value("${platform.client.secret}") String secret) {
        if (secret == null || secret.isBlank() || secret.startsWith("${"))
            throw new org.portage.authorizationserver.error.InvalidIdentityConfigurationException("Client secret configuration is required");
        var client = RegisteredClient.withId("portage-demo-client")
                .clientId("demo-client").clientSecret(encoder.encode(secret))
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(SubjectGrantAuthenticationToken.GRANT_TYPE)
                .scope("hello.read").build();
        return new InMemoryRegisteredClientRepository(client);
    }

    /** Configure l'issuer inscrit dans les jetons émis par ce serveur. */
    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer("http://127.0.0.1:9000").build();
    }

    /** Relie le traitement du grant personnalisé aux utilisateurs et au signataire JWT. */
    @Bean
    SubjectGrantAuthenticationProvider subjectGrantProvider(UserRegistry users, JwtEncoder encoder) {
        return new SubjectGrantAuthenticationProvider(users, encoder, "http://127.0.0.1:9000", java.time.Clock.systemUTC());
    }

    /**
     * Configure les endpoints OAuth. Le convertisseur lit les paramètres du
     * grant personnalisé et le provider valide la demande puis fait signer le JWT.
     */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, SubjectGrantAuthenticationProvider provider) throws Exception {
        var configurer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, server -> server.tokenEndpoint(token -> token
                        .accessTokenRequestConverter(new SubjectGrantAuthenticationConverter())
                        .authenticationProvider(provider)
                        .errorResponseHandler((request, response, exception) -> {
                            // Une indisponibilité du Key Service doit apparaître comme une erreur temporaire, pas comme une erreur d'identifiants.
                            if (exception instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauth
                                    && "temporarily_unavailable".equals(oauth.getError().getErrorCode())) {
                                response.setStatus(503);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.setHeader("Cache-Control", "no-store");
                                response.getWriter().write("{\"error\":\"temporarily_unavailable\"}");
                            } else
                                new org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler()
                                        .onAuthenticationFailure(request, response, exception);
                        })))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"unauthorized\"}");
                }));
        return http.build();
    }

    /**
     * Chaîne de dernier recours : aucune route non couverte par les chaînes
     * précédentes n'est exposée implicitement.
     */
    @Bean
    @Order(2)
    SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.anyRequest().denyAll()).csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
