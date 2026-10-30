package org.portage.authorizationserver.cucumber;

import io.cucumber.java.en.*;
import io.cucumber.spring.CucumberContextConfiguration;
import org.portage.authorizationserver.AuthorizationFixture;
import org.portage.authorizationserver.PkceBrowser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AuthorizationCodePkceSteps {
    @Value("${local.server.port}") int port;
    private HttpResponse<String> response;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { AuthorizationFixture.properties(registry); }
    @Given("the authorization server is running")
    public void running() { assertThat(port).isPositive(); }
    @When("a valid PKCE code is exchanged")
    public void valid() throws Exception { response = PkceBrowser.exchange(port, true, true); }
    @When("a PKCE code is exchanged with the wrong verifier")
    public void wrongVerifier() throws Exception { response = PkceBrowser.exchange(port, false, true); }
    @When("a PKCE code is exchanged by an unknown client")
    public void unknownClient() throws Exception { response = PkceBrowser.exchange(port, true, false); }
    @Then("a signed access token and refresh token are returned")
    public void accepted() throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        var body = new tools.jackson.databind.ObjectMapper().readTree(response.body());
        assertThat(body.hasNonNull("refresh_token")).isTrue();
        var jwt = com.nimbusds.jwt.SignedJWT.parse(body.get("access_token").asText());
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("subject-1");
    }
    @Then("the PKCE exchange is rejected without exposing credentials")
    public void denied() { rejected(400, "invalid_grant"); }
    @Then("client authentication is rejected without exposing credentials")
    public void clientDenied() { rejected(401, "invalid_client"); }
    private void rejected(int status, String error) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(response.body()).get("error").asText()).isEqualTo(error);
        assertThat(response.body()).doesNotContain(AuthorizationFixture.CLIENT_SECRET, AuthorizationFixture.password(), "stackTrace");
    }
}
