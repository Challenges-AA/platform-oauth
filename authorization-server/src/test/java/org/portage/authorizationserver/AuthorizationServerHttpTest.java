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
    /** Le client doit être connu et le sujet demandé doit correspondre à l'utilisateur. */
    void emitsOnlyForKnownClientAndMatchingUserSubject() throws Exception {
        var form = AuthorizationFixture.form();
        var good = AuthorizationFixture.request(port, "demo-client", AuthorizationFixture.CLIENT_SECRET, form);
        assertThat(good.statusCode()).isEqualTo(200);
        var json = new tools.jackson.databind.ObjectMapper().readTree(good.body());
        assertThat(json.hasNonNull("access_token")).isTrue();
        assertThat(json.get("token_type").asText()).isEqualToIgnoringCase("Bearer");
        assertThat(good.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(AuthorizationFixture.request(port, "unknown-client", AuthorizationFixture.CLIENT_SECRET, form).statusCode()).isEqualTo(401);
        assertThat(AuthorizationFixture.request(port, "demo-client", "wrong", form).statusCode()).isEqualTo(401);
        form.put("subject", "subject-2");
        assertThat(AuthorizationFixture.request(port, "demo-client", AuthorizationFixture.CLIENT_SECRET, form).statusCode()).isEqualTo(400);
    }

    @Test
    /** Un grant, scope ou mot de passe non autorisé est refusé sans fuite de détails. */
    void rejectsUnapprovedGrantScopeAndPasswordWithSanitizedErrors() throws Exception {
        for (String parameter : new String[]{"password", "scope", "grant_type"}) {
            var form = AuthorizationFixture.form();
            form.put(parameter, "unapproved");
            var response = AuthorizationFixture.request(port, "demo-client", AuthorizationFixture.CLIENT_SECRET, form);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body().contains(AuthorizationFixture.CLIENT_SECRET)).isFalse();
            assertThat(response.body().contains("stackTrace")).isFalse();
        }
    }

    @Test
    /** Les scopes répétés sont normalisés en ensemble plutôt que traités comme plusieurs droits. */
    void normalizesRepeatedScopeWithoutServerError() throws Exception {
        var form = AuthorizationFixture.form();
        form.put("scope", "hello.read hello.read");
        assertThat(AuthorizationFixture.request(port, "demo-client", AuthorizationFixture.CLIENT_SECRET, form).statusCode()).isEqualTo(200);
    }
}
