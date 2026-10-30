package org.portage.authorizationserver;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
/** Vérifie par HTTP l'émission de jetons, les refus OAuth et le proxy JWKS public. */
class AuthorizationServerHttpTest {
    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AuthorizationFixture.properties(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        AuthorizationFixture.cleanup();
    }

    @Test
    /** Le JWKS expose uniquement les parties publiques, jamais le secret privé d'une clé. */
    void exposesOnlyPublicKeysThroughIssuerEndpoint() throws Exception {
        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port + "/oauth2/jwks")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("\"d\"");
        assertThat(com.nimbusds.jose.jwk.JWKSet.parse(response.body()).getKeys()).isNotEmpty();
    }

    @Test
    void rejectsRemovedAndUnsupportedGrants() throws Exception {
        for (String grant : new String[]{"urn:portage:params:oauth:grant-type:subject", "password", "client_credentials"}) {
            var response = AuthorizationFixture.request(port, "demo-client", AuthorizationFixture.CLIENT_SECRET,
                    java.util.Map.of("grant_type", grant));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(new tools.jackson.databind.ObjectMapper().readTree(response.body()).get("error").asText())
                    .isEqualTo(grant.equals("client_credentials") ? "unauthorized_client" : "unsupported_grant_type");
            assertThat(response.body()).doesNotContain(AuthorizationFixture.CLIENT_SECRET, "stackTrace");
        }
    }
}
