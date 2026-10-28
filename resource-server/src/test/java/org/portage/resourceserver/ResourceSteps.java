package org.portage.resourceserver;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ResourceSteps {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;
    private String bearer;
    private ResponseEntity<Map> response;

    @Given("a valid access token with scope {string}")
    /** Prépare une clé de test publiée et un JWT portant le scope demandé par le scénario. */
    public void validToken(String scope) throws Exception {
        JwksFixture.publish("key-a", "key-b");
        bearer = JwksFixture.token("key-b", JwksFixture.KEY_B, scope, 0, 300, -1,
                "http://127.0.0.1:9000", "portage-api", "ES256");
    }

    @Given("no access token")
    /** Simule un client qui ne fournit pas d'en-tête Bearer. */
    public void noToken() {
        bearer = null;
    }

    @When("the client requests hello")
    /** Appelle l'endpoint de ressource avec le jeton courant du scénario. */
    public void requestHello() throws Exception {
        response = TestHttp.hello(port, bearer);
    }

    @Then("the response status is {int}")
    public void responseStatus(int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
    }

    @Then("the greeting subject is {string}")
    public void greetingSubject(String subject) {
        assertThat(response.getBody()).containsEntry("message", "HelloWorld").containsEntry("subject", subject);
    }

    @Then("the error is {string}")
    public void errorBody(String code) {
        assertThat(response.getBody()).containsEntry("error", code);
    }
}
