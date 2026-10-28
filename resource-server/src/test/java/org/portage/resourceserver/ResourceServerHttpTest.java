package org.portage.resourceserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
/** Vérifie par HTTP l'acceptation des JWT valides et le rejet des jetons non conformes. */
class ResourceServerHttpTest {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("resource-server.jwks-uri", JwksFixture::uri);
    }

    @BeforeEach
    /** Réinitialise les clés publiées avant chaque scénario indépendant. */
    void publishInitialKey() {
        JwksFixture.publish("key-a");
    }

    @Test
    /** Accepte les kids publiés et rejette un jeton référençant une clé absente du JWKS. */
    void acceptsPublishedCurrentAndNextKeysButRejectsUnknownKid() throws Exception {
        JwksFixture.publish("key-a", "key-b");
        assertThat(call(JwksFixture.token("key-a", JwksFixture.KEY_A, "hello.read", 0, 300, -1,
                "http://127.0.0.1:9000", "portage-api", "ES256")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(JwksFixture.token("key-b", JwksFixture.KEY_B, "hello.read", 0, 300, -1,
                "http://127.0.0.1:9000", "portage-api", "ES256")).getStatusCode()).isEqualTo(HttpStatus.OK);
        String unknown = JwksFixture.token("key-c", JwksFixture.KEY_B, "hello.read", 0, 300, -1,
                "http://127.0.0.1:9000", "portage-api", "ES256");
        assertThat(call(unknown).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    /** Différencie l'absence d'authentification (401) du scope insuffisant (403). */
    void respondsWithExpectedBodyAndSeparatesAuthenticationFromAuthorization() throws Exception {
        ResponseEntity<Map> good = call(JwksFixture.token("key-a", JwksFixture.KEY_A, "hello.read", 0, 300, -1,
                "http://127.0.0.1:9000", "portage-api", "ES256"));
        assertThat(good.getBody()).containsEntry("message", "HelloWorld").containsEntry("subject", "subject-1");
        var unauthorized = TestHttp.request(port, null);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        ResponseEntity<Map> forbidden = call(JwksFixture.token("key-a", JwksFixture.KEY_A, "other.read", 0, 300, -1,
                "http://127.0.0.1:9000", "portage-api", "ES256"));
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).containsEntry("error", "forbidden");
    }

    @Test
    /** Vérifie algorithme, signature, issuer, audience et claims temporels obligatoires. */
    void rejectsAlgorithmAndInvalidClaims() throws Exception {
        for (String[] claims : new String[][]{
                {"ES256", "https://wrong", "portage-api", "0", "300", "-1"},
                {"ES256", "http://127.0.0.1:9000", "wrong", "0", "300", "-1"},
                {"ES256", "http://127.0.0.1:9000", "portage-api", "-300", "-1", "-1"},
                {"ES256", "http://127.0.0.1:9000", "portage-api", "0", "300", "300"}}) {
            String token = JwksFixture.token("key-a", JwksFixture.KEY_A, "hello.read",
                    Long.parseLong(claims[3]), Long.parseLong(claims[4]), Long.parseLong(claims[5]), claims[1], claims[2], claims[0]);
            assertThat(call(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(call(JwksFixture.tokenWithoutNotBefore()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(JwksFixture.unsignedToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String signed = JwksFixture.token("key-a", JwksFixture.KEY_A, "hello.read", 0, 300, -1,
                "http://127.0.0.1:9000", "portage-api", "ES256");
        assertThat(call(JwksFixture.withUnexpectedAlgorithm(signed)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Change significant signature bits, not unused Base64 padding bits.
        int index = signed.lastIndexOf('.') + 1;
        char first = signed.charAt(index);
        assertThat(call(signed.substring(0, index) + (first == 'A' ? 'B' : 'A') + signed.substring(index + 1)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Envoie le jeton au endpoint protégé et lit son statut ainsi que son JSON de réponse. */
    private ResponseEntity<Map> call(String token) throws Exception {
        return TestHttp.hello(port, token);
    }
}
