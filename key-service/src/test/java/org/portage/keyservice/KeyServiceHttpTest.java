package org.portage.keyservice;

import com.nimbusds.jose.util.JSONObjectUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
/** Vérifie les routes HTTP, l'authentification et la séparation des rôles du Key Service. */
class KeyServiceHttpTest {
    static final String SIGN = UUID.randomUUID().toString(), ADMIN = UUID.randomUUID().toString();
    static final Path DIRECTORY;

    static {
        try {
            DIRECTORY = Files.createTempDirectory(Path.of("target"), "key-service-test-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    /** Utilise un répertoire temporaire et des secrets de test générés à l'exécution. */
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.keys.directory", DIRECTORY::toString);
        registry.add("platform.keys.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("platform.keys.signing-secret", () -> SIGN);
        registry.add("platform.keys.admin-secret", () -> ADMIN);
    }

    /** Envoie une requête locale avec ou sans authentification Basic. */
    private HttpResponse<String> request(String path, String user, String secret, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (user != null)
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes()));
        if (body != null)
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    /** Le signer signe seulement, l'admin tourne les clés et le JWKS reste public-only. */
    void protectsSignatureAndRotationSeparatelyAndPublishesOnlyPublicKeys() throws Exception {
        assertThat(request("/internal/sign", null, null, "{}").statusCode()).isEqualTo(401);
        assertThat(request("/internal/sign", "signer", "wrong", "{}").statusCode()).isEqualTo(401);
        assertThat(request("/internal/rotate", "signer", SIGN, "{}").statusCode()).isEqualTo(403);
        assertThat(request("/internal/sign", "admin", ADMIN, "{}").statusCode()).isEqualTo(403);
        assertThat(request("/internal/sign", "signer", SIGN, "{}").statusCode()).isEqualTo(400);
        long now = Instant.now().getEpochSecond();
        String claims = JSONObjectUtils.toJSONString(Map.of("iss", "http://127.0.0.1:9000", "sub", "subject-1", "aud", List.of("portage-api"),
                "iat", now, "nbf", now, "exp", now + 300, "jti", UUID.randomUUID().toString(), "scope", "hello.read"));
        var signed = request("/internal/sign", "signer", SIGN, claims);
        assertThat(signed.statusCode()).isEqualTo(200);
        assertThat(signed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(JSONObjectUtils.parse(signed.body())).containsKey("token");
        assertThat(request("/internal/rotate", "admin", ADMIN, "{}").statusCode()).isEqualTo(200);
        var publicKeys = request("/oauth2/jwks", null, null, null);
        assertThat(publicKeys.statusCode()).isEqualTo(200);
        assertThat(publicKeys.body()).doesNotContain("\"d\"");
        assertThat(com.nimbusds.jose.jwk.JWKSet.parse(publicKeys.body()).getKeys()).hasSize(2);
        assertThat(request("/other", "signer", SIGN, null).statusCode()).isEqualTo(403);
    }

    @Test
    /** Les comptes opérationnels doivent utiliser des secrets forts et différents. */
    void rejectsWeakOrSharedCredentials() {
        var security = new KeySecurityConfiguration();
        assertThatThrownBy(() -> security.users("short", ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> security.users(SIGN, "short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> security.users(SIGN, SIGN)).isInstanceOf(IllegalArgumentException.class);
    }
}
