package org.portage.authorizationserver.cucumber;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.cucumber.spring.CucumberContextConfiguration;
import org.portage.authorizationserver.AuthorizationFixture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
/** Étapes Cucumber qui exercent le grant personnalisé via de vraies requêtes HTTP. */
public class SubjectGrantSteps {
    @Value("${local.server.port}")
    int port;
    private HttpResponse<String> response;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AuthorizationFixture.properties(registry);
    }

    @io.cucumber.java.AfterAll
    public static void cleanup() throws Exception {
        AuthorizationFixture.cleanup();
    }

    @Given("the authorization server is running")
    public void running() {
        // Le port aléatoire n'est disponible qu'après le démarrage du contexte Spring.
        assertThat(port).isPositive();
    }

    @When("a subject grant is submitted with an invalid identity")
    public void invalidIdentity() throws Exception {
        var form = AuthorizationFixture.form();
        form.put("password", "wrong");
        response = AuthorizationFixture.request(port, "demo-client", AuthorizationFixture.CLIENT_SECRET, form);
    }

    @When("a subject grant is submitted by an unknown client")
    public void unknownClient() throws Exception {
        response = AuthorizationFixture.request(port, "unknown-client", "wrong", AuthorizationFixture.form());
    }

    @When("a valid subject grant is submitted")
    public void valid() throws Exception {
        response = AuthorizationFixture.request(port, "demo-client", AuthorizationFixture.CLIENT_SECRET, AuthorizationFixture.form());
    }

    @Then("a signed access token is returned")
    public void accepted() {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(response.body()).hasNonNull("access_token")).isTrue();
    }

    @Then("the token request is rejected without exposing credentials")
    public void rejected() {
        assertThat(response.statusCode()).isEqualTo(400);
        sanitized();
    }

    @Then("the client authentication is rejected without exposing credentials")
    public void clientRejected() {
        assertThat(response.statusCode()).isEqualTo(401);
        sanitized();
    }

    private void sanitized() {
        // Les réponses OAuth ne doivent jamais renvoyer le secret du client ni une stack trace.
        assertThat(response.body().contains(AuthorizationFixture.CLIENT_SECRET)).isFalse();
        assertThat(response.body().contains("stackTrace")).isFalse();
    }
}
