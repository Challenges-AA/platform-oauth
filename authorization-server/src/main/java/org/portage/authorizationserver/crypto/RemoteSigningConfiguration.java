package org.portage.authorizationserver.crypto;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Configuration
public class RemoteSigningConfiguration {
    @Bean
    JwtEncoder jwtEncoder(@Value("${platform.signing.url}") String url,
                          @Value("${platform.signing.secret}") String secret) {
        if (secret == null || secret.length() < 32 || secret.startsWith("${"))
            throw new IllegalArgumentException("Signing service credentials are required");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        String authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                ("signer:" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return parameters -> {
            try {
                JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
                parameters.getClaims().getClaims().forEach((name, value) -> builder.claim(name,
                        value instanceof java.time.Instant instant ? java.util.Date.from(instant) : value));
                var request = HttpRequest.newBuilder(URI.create(url + "/internal/sign"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(builder.build().toJSONObject()))).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new JwtEncodingException("Signing service unavailable");
                String token = JSONObjectUtils.getString(JSONObjectUtils.parse(response.body()), "token");
                SignedJWT signed = SignedJWT.parse(token);
                return new Jwt(token, parameters.getClaims().getIssuedAt(), parameters.getClaims().getExpiresAt(),
                        signed.getHeader().toJSONObject(), parameters.getClaims().getClaims());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JwtEncodingException("Signing service unavailable");
            } catch (Exception e) {
                throw new JwtEncodingException("Signing service unavailable");
            }
        };
    }
}
