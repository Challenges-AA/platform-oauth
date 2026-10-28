package org.portage.keyservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class KeySecurityConfiguration {
    @Bean
    UserDetailsService users(@Value("${platform.keys.signing-secret}") String signing,
                             @Value("${platform.keys.admin-secret}") String admin) {
        if (signing.length() < 32 || admin.length() < 32 || signing.equals(admin)
                || signing.startsWith("${") || admin.startsWith("${"))
            throw new IllegalArgumentException("Distinct signing and administration secrets of at least 32 characters are required");
        var encoder = new BCryptPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("signer").password("{bcrypt}" + encoder.encode(signing)).roles("SIGN").build(),
                User.withUsername("admin").password("{bcrypt}" + encoder.encode(admin)).roles("ROTATE").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(org.springframework.http.HttpMethod.GET, "/oauth2/jwks").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/sign").hasRole("SIGN")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/rotate").hasRole("ROTATE")
                        .anyRequest().denyAll())
                .httpBasic(b -> b.authenticationEntryPoint((q, r, e) -> r.setStatus(401))).build();
    }
}
