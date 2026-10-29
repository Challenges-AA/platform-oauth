package org.portage.authorizationserver.config;

import java.time.*;
import java.util.List;
import org.portage.authorizationserver.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class AuthorizationSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain publicKeysChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/oauth2/jwks").authorizeHttpRequests(a -> a
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/oauth2/jwks").permitAll()
                .anyRequest().denyAll()).build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(PasswordEncoder encoder,
            @Value("${platform.client.secret}") String secret,
            @Value("${platform.authorization.redirect-uri:http://127.0.0.1:8080/callback}") String redirect,
            @Value("${platform.authorization.demo-redirect-uri:http://127.0.0.1:9000/demo/callback}") String demoRedirect,
            @Value("${platform.authorization.legacy-grant-enabled:false}") boolean legacy) {
        if (secret == null || secret.isBlank() || secret.startsWith("${"))
            throw new org.portage.authorizationserver.error.InvalidIdentityConfigurationException("Client secret configuration is required");
        var tokens = TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5))
                .authorizationCodeTimeToLive(Duration.ofMinutes(2)).refreshTokenTimeToLive(Duration.ofHours(8))
                .reuseRefreshTokens(false).build();
        var settings = ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build();
        var confidential = RegisteredClient.withId("portage-demo-client")
                .clientId("demo-client").clientSecret(encoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirect).scope("hello.read").clientSettings(settings).tokenSettings(tokens);
        if (legacy) confidential.authorizationGrantType(SubjectGrantAuthenticationToken.GRANT_TYPE);
        var publicClient = RegisteredClient.withId("portage-public-client").clientId("portage-public")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirect).redirectUri(demoRedirect).scope("hello.read")
                .clientSettings(settings).tokenSettings(tokens).build();
        return new InMemoryRegisteredClientRepository(confidential.build(), publicClient);
    }

    @Bean
    UserDetailsService userDetailsService(UserRegistry users) {
        return username -> {
            var user = users.find(username).orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
            return User.withUsername(user.subject()).password(user.passwordHash()).roles("USER").build();
        };
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer("http://127.0.0.1:9000").build();
    }

    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(JwtEncoder encoder) {
        var jwt = new JwtGenerator(encoder);
        jwt.setJwtCustomizer(context -> {
            context.getJwsHeader().algorithm(SignatureAlgorithm.ES256).type("JWT");
            context.getClaims().audience(new java.util.ArrayList<>(List.of("portage-api")))
                    .claim("scope", String.join(" ", context.getAuthorizedScopes()));
        });
        return new DelegatingOAuth2TokenGenerator(jwt, new RotatingRefreshTokenGenerator(Clock.systemUTC()));
    }

    @Bean
    SubjectGrantAuthenticationProvider subjectGrantProvider(UserRegistry users, JwtEncoder encoder) {
        return new SubjectGrantAuthenticationProvider(users, encoder, "http://127.0.0.1:9000", Clock.systemUTC());
    }

    @Bean @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, SubjectGrantAuthenticationProvider legacyProvider,
            RegisteredClientRepository clients, RotatingAuthorizationService authorizations, TransactionTemplate transactions,
            @Value("${platform.authorization.legacy-grant-enabled:false}") boolean legacy) throws Exception {
        var configurer = new OAuth2AuthorizationServerConfigurer();
        var publicAuthentication = new PublicRefreshClientAuthentication(clients);
        http.securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, server -> server
                    .clientAuthentication(client -> client
                        .authenticationConverters(converters -> converters.addFirst(publicAuthentication))
                        .authenticationProviders(providers -> providers.addFirst(publicAuthentication)))
                    .authorizationEndpoint(endpoint -> endpoint.authenticationProviders(providers -> providers.forEach(provider -> {
                        if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider code)
                            code.setAuthenticationValidator(new OAuth2AuthorizationCodeRequestAuthenticationValidator().andThen(context -> {
                                OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
                                Object challenge = request.getAdditionalParameters().get("code_challenge");
                                if (!"S256".equals(request.getAdditionalParameters().get("code_challenge_method"))
                                        || !(challenge instanceof String value) || !value.matches("[A-Za-z0-9_-]{43}"))
                                    throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                                            new OAuth2Error("invalid_request"), request);
                            }));
                    })))
                    .tokenEndpoint(token -> {
                        if (legacy) token.accessTokenRequestConverter(new SubjectGrantAuthenticationConverter())
                                .authenticationProvider(legacyProvider);
                        token.errorResponseHandler((request, response, exception) -> {
                            if (exception instanceof OAuth2AuthenticationException oauth
                                    && "temporarily_unavailable".equals(oauth.getError().getErrorCode())) {
                                response.setStatus(503);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.setHeader("Cache-Control", "no-store");
                                response.getWriter().write("{\"error\":\"temporarily_unavailable\"}");
                            } else new org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler()
                                    .onAuthenticationFailure(request, response, exception);
                        });
                    }))
                .addFilterBefore(new TokenTransactionFilter(transactions, authorizations),
                        org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        // Machine endpoints must never redirect to the browser login form,
                        // including when no client converter recognized a malformed request.
                        .defaultAuthenticationEntryPointFor((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                            response.setHeader("Cache-Control", "no-store");
                            response.setHeader("Pragma", "no-cache");
                            response.getWriter().write("{\"error\":\"invalid_client\",\"error_description\":"
                                    + "\"Client authentication was not recognized. Check Content-Type and client authentication parameters.\"}");
                        }, request -> "/oauth2/token".equals(request.getServletPath())
                                || "/oauth2/revoke".equals(request.getServletPath()))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean @Order(2)
    SecurityFilterChain applicationChain(HttpSecurity http, UserDetailsService users, PasswordEncoder encoder) throws Exception {
        var login = new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);
        login.setPasswordEncoder(encoder);
        http.authenticationProvider(login);
        http.authorizeHttpRequests(a -> a.requestMatchers("/login", "/error").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/", "/demo/config", "/demo/callback", "/demo/assets/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                org.springframework.http.HttpStatus.UNAUTHORIZED),
                        request -> "/demo/config".equals(request.getServletPath())))
                // Retain the saved authorization request when login was initiated by OAuth.
                .formLogin(loginForm -> loginForm.defaultSuccessUrl("/", false));
        return http.build();
    }
}
