package org.portage.authorizationserver;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.portage.authorizationserver.security.RotatingAuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, browser login, Spring PKCE and durable refresh rotation. No fixed sleeps. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationCodePkceHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired RotatingAuthorizationService authorizations;
    @Autowired RegisteredClientRepository clients;
    @Autowired DataSource source;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REDIRECT = "http://127.0.0.1:8080/callback";
    private static final String VERIFIER = "a".repeat(64);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { AuthorizationFixture.properties(registry); }

    @Test
    void demoRequiresLoginAndDirectLoginOpensTheHomePage() throws Exception {
        var anonymous = browser();
        var protectedPage = get(anonymous, "/");
        assertThat(protectedPage.statusCode()).isEqualTo(302);
        assertThat(URI.create(protectedPage.headers().firstValue("Location").orElseThrow()).getPath()).isEqualTo("/login");
        assertThat(get(browser(), "/demo/config").statusCode()).isEqualTo(401);

        var browser = browser();
        var connected = submitLogin(browser);
        assertThat(connected.statusCode()).isEqualTo(302);
        assertThat(URI.create(connected.headers().firstValue("Location").orElseThrow()).getPath()).isEqualTo("/");
        var home = get(browser, "/");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Démarrer une autorisation PKCE");
        assertThat(home.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(home.headers().firstValue("Content-Security-Policy").orElse("")).contains("script-src 'self'", "frame-ancestors 'none'");
        var configuration = json(get(browser, "/demo/config", "application/json"));
        assertThat(configuration.get("clientId").asText()).isEqualTo("portage-public");
        assertThat(configuration.get("redirectUri").asText()).isEqualTo("http://127.0.0.1:9000/demo/callback");
        assertThat(configuration.get("subject").asText()).isEqualTo("subject-1");
        assertThat(configuration.has("clientSecret")).isFalse();
        assertThat(get(browser, "/demo/assets/demo.js").statusCode()).isEqualTo(200);
        assertThat(get(browser, "/demo/assets/demo.css").statusCode()).isEqualTo(200);
    }

    @Test
    void loginResumesSavedAuthorizationInsteadOfForcingTheHomePage() throws Exception {
        var browser = browser();
        assertThat(get(browser, authorizeUrl("portage-public", challenge(), "S256")).statusCode()).isEqualTo(302);
        var connected = submitLogin(browser);
        var saved = URI.create(connected.headers().firstValue("Location").orElseThrow());
        assertThat(saved.getPath()).isEqualTo("/oauth2/authorize");
        String code = code(get(browser, saved.getRawPath() + "?" + saved.getRawQuery()));
        assertThat(code).isNotBlank();
    }

    @Test
    void publicDemoCallbackIsRegisteredAndProducesAnExchangeableCode() throws Exception {
        var browser = login();
        String callback = "http://127.0.0.1:9000/demo/callback";
        String authorize = authorizeUrl("portage-public", challenge(), "S256")
                .replace(URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8), URLEncoder.encode(callback, StandardCharsets.UTF_8));
        var response = get(browser, authorize);
        assertThat(response.statusCode()).isEqualTo(302);
        var location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertThat(location.toString()).startsWith(callback + "?").contains("code=", "state=");
        assertThat(get(browser, location.getRawPath() + "?" + location.getRawQuery()).statusCode()).isEqualTo(200);
        String code = Arrays.stream(location.getRawQuery().split("&")).filter(p -> p.startsWith("code="))
                .map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow();
        var tokens = json(post(browser(), "/oauth2/token", Map.of("grant_type", "authorization_code", "client_id", "portage-public",
                "code", code, "redirect_uri", callback, "code_verifier", VERIFIER), null));
        assertThat(tokens.hasNonNull("access_token")).isTrue();
        assertThat(tokens.hasNonNull("refresh_token")).isTrue();
    }

    @Test
    void malformedTokenRequestsReturnJsonInsteadOfRedirectingToLogin() throws Exception {
        var missingVerifier = Map.of("grant_type", "authorization_code", "client_id", "portage-public",
                "code", "diagnostic-code-inexistant", "redirect_uri", REDIRECT);
        for (String accept : List.of("text/html", "*/*", "application/json")) {
            for (String contentType : List.of("application/x-www-form-urlencoded", "application/json")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/oauth2/token"))
                        .header("Accept", accept).header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(encode(missingVerifier))).build();
                var response = browser().send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.headers().firstValue("Location")).isEmpty();
                assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
                assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
                assertThat(JSON.readTree(response.body()).get("error").asText()).isEqualTo("invalid_client");
            }
        }
    }

    @Test
    void browserAuthorizationStillRedirectsAnonymousUserToLogin() throws Exception {
        var response = get(browser(), authorizeUrl("portage-public", challenge(), "S256"));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(URI.create(response.headers().firstValue("Location").orElseThrow()).getPath()).isEqualTo("/login");
    }

    @Test
    void publicClientExchangesCodeAndRefreshesWithBoundSubjectScopeAndAudience() throws Exception {
        JsonNode initial = issue("portage-public");
        assertThat(initial.hasNonNull("refresh_token")).isTrue();
        JsonNode next = json(refresh("portage-public", initial.get("refresh_token").asText()));
        assertThat(next.get("refresh_token").asText()).isNotEqualTo(initial.get("refresh_token").asText());
        var jwt = com.nimbusds.jwt.SignedJWT.parse(next.get("access_token").asText()).getJWTClaimsSet();
        assertThat(jwt.getSubject()).isEqualTo("subject-1");
        assertThat(jwt.getAudience()).containsExactly("portage-api");
        assertThat(jwt.getStringClaim("scope")).isEqualTo("hello.read");
        assertThat(jwt.getExpirationTime().toInstant()).isEqualTo(jwt.getIssueTime().toInstant().plusSeconds(300));
        assertThat(next.get("expires_in").asInt()).isBetween(298, 300);
    }

    @Test
    void confidentialClientUsesPkceAndBasicAuthenticationForRefresh() throws Exception {
        var tokens = issue("demo-client");
        var response = refresh("demo-client", tokens.get("refresh_token").asText());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).hasNonNull("refresh_token")).isTrue();
    }

    @Test
    void replayRevokesNewestTokenAndPersistsAcrossServiceRecreation() throws Exception {
        String first = issue("portage-public").get("refresh_token").asText();
        String second = json(refresh("portage-public", first)).get("refresh_token").asText();
        // New service instance reads the same stored authorizations and replay history.
        var restored = new RotatingAuthorizationService(new JdbcTemplate(source), clients,
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)));
        assertThat(restored.findByToken(second, OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
        invalidGrant(refresh("portage-public", first));
        invalidGrant(refresh("portage-public", second));
        assertThat(restored.findByToken(second, OAuth2TokenType.REFRESH_TOKEN).getRefreshToken().isInvalidated()).isTrue();
    }

    @Test
    void wrongClientAndScopeEscalationFailWithoutRevokingOwner() throws Exception {
        String token = issue("portage-public").get("refresh_token").asText();
        invalidGrant(refresh("demo-client", token));
        var response = post(browser(), "/oauth2/token", new LinkedHashMap<>(Map.of(
                "grant_type", "refresh_token", "client_id", "portage-public", "refresh_token", token, "scope", "admin")), null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").asText()).isEqualTo("invalid_scope");
        assertThat(refresh("portage-public", token).statusCode()).isEqualTo(200);
    }

    @Test
    void expiredAndRevokedRefreshTokensAreRejected() throws Exception {
        String token = issue("portage-public").get("refresh_token").asText();
        var authorization = authorizations.findByToken(token, OAuth2TokenType.REFRESH_TOKEN);
        authorizations.save(OAuth2Authorization.from(authorization).refreshToken(new OAuth2RefreshToken(token,
                Instant.now().minusSeconds(100), Instant.now().minusSeconds(1))).build());
        invalidGrant(refresh("portage-public", token));
        String active = issue("portage-public").get("refresh_token").asText();
        var revoked = post(browser(), "/oauth2/revoke", Map.of("client_id", "portage-public", "token", active,
                "token_type_hint", "refresh_token"), null);
        assertThat(revoked.statusCode()).isEqualTo(200);
        invalidGrant(refresh("portage-public", active));
        assertThat(post(browser(), "/oauth2/revoke", Map.of("client_id", "portage-public", "token", "unknown"), null).statusCode()).isEqualTo(200);
    }

    @Test
    void absoluteFamilyExpiryCannotBeExtendedByRotation() throws Exception {
        String token = issue("portage-public").get("refresh_token").asText();
        var authorization = authorizations.findByToken(token, OAuth2TokenType.REFRESH_TOKEN);
        assertThat(authorization.getRefreshToken().getToken().getExpiresAt()).isEqualTo(
                authorization.getRefreshToken().getToken().getIssuedAt().plus(Duration.ofHours(8)));
        authorizations.save(OAuth2Authorization.from(authorization)
                .attribute(org.portage.authorizationserver.security.RotatingRefreshTokenGenerator.FAMILY_EXPIRES_AT,
                        Instant.now().minusSeconds(1)).build());
        invalidGrant(refresh("portage-public", token));
    }

    @Test
    void expiredCodeAndMismatchedRedirectAreRejected() throws Exception {
        var browser = login();
        String code = code(get(browser, authorizeUrl("portage-public", challenge(), "S256")));
        var form = new LinkedHashMap<>(Map.of("grant_type", "authorization_code", "client_id", "portage-public",
                "code", code, "redirect_uri", "http://127.0.0.1:8080/wrong", "code_verifier", VERIFIER));
        invalidGrant(post(browser(), "/oauth2/token", form, null));
        var authorization = authorizations.findByToken(code, new OAuth2TokenType("code"));
        authorizations.save(OAuth2Authorization.from(authorization).token(new OAuth2AuthorizationCode(code,
                Instant.now().minusSeconds(100), Instant.now().minusSeconds(1))).build());
        form.put("redirect_uri", REDIRECT);
        invalidGrant(post(browser(), "/oauth2/token", form, null));
    }

    @Test
    void simultaneousRefreshHasOneWinnerThenRevokesItsFamily() throws Exception {
        String token = issue("portage-public").get("refresh_token").asText();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<HttpResponse<String>> call = () -> { start.await(); return refresh("portage-public", token); };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 400);
            var winner = results.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
            invalidGrant(refresh("portage-public", json(winner).get("refresh_token").asText()));
        }
    }

    @Test
    void requiresS256AndRejectsWrongVerifierAndCodeReuse() throws Exception {
        var browser = login();
        var noPkce = get(browser, authorizeUrl("portage-public", null, null));
        assertOAuthError(noPkce);
        assertOAuthError(get(browser, authorizeUrl("portage-public", VERIFIER, "plain")));
        String code = code(get(browser, authorizeUrl("portage-public", challenge(), "S256")));
        var form = new LinkedHashMap<>(Map.of("grant_type", "authorization_code", "client_id", "portage-public",
                "code", code, "redirect_uri", REDIRECT, "code_verifier", "b".repeat(64)));
        form.put("portage.public-refresh", "true"); // An HTTP parameter cannot select the refresh-only client provider.
        assertThat(post(browser(), "/oauth2/token", form, null).statusCode()).isBetween(400, 401);
        // A fresh authorization succeeds once only.
        String fresh = code(get(browser, authorizeUrl("portage-public", challenge(), "S256")));
        form.put("code", fresh); form.put("code_verifier", VERIFIER);
        var successful = post(browser(), "/oauth2/token", form, null);
        assertThat(successful.statusCode()).isEqualTo(200);
        invalidGrant(post(browser(), "/oauth2/token", form, null));
        invalidGrant(refresh("portage-public", json(successful).get("refresh_token").asText()));
    }

    @Test
    void browserSessionAndDatabaseArePersistentAndProtected() throws Exception {
        var browser = login();
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class)).isPositive();
        // Reuse the persisted security context without submitting the user's password again.
        assertThat(code(get(browser, authorizeUrl("portage-public", challenge(), "S256")))).isNotBlank();
        var hikari = (com.zaxxer.hikari.HikariDataSource) source;
        assertThat(hikari.getJdbcUrl()).contains("CIPHER=AES");
    }

    private JsonNode issue(String client) throws Exception {
        String code = code(get(login(), authorizeUrl(client, challenge(), "S256")));
        return json(post(browser(), "/oauth2/token", Map.of("grant_type", "authorization_code", "client_id", client,
                "code", code, "redirect_uri", REDIRECT, "code_verifier", VERIFIER), client.equals("demo-client") ? client : null));
    }
    private HttpResponse<String> refresh(String client, String token) throws Exception {
        return post(browser(), "/oauth2/token", Map.of("grant_type", "refresh_token", "client_id", client,
                "refresh_token", token), client.equals("demo-client") ? client : null);
    }
    private HttpClient login() throws Exception {
        var browser = browser();
        var result = submitLogin(browser);
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.headers().firstValue("Location").orElse("")).doesNotContain("error");
        return browser;
    }
    private HttpResponse<String> submitLogin(HttpClient browser) throws Exception {
        String page = get(browser, "/login").body();
        var matcher = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page);
        assertThat(matcher.find()).isTrue();
        return post(browser, "/login", Map.of("username", "user1", "password", AuthorizationFixture.form().get("password"),
                "_csrf", matcher.group(1)), null);
    }
    private static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }
    private String authorizeUrl(String client, String challenge, String method) {
        var params = new LinkedHashMap<>(Map.of("response_type", "code", "client_id", client,
                "redirect_uri", REDIRECT, "scope", "hello.read", "state", UUID.randomUUID().toString()));
        if (challenge != null) { params.put("code_challenge", challenge); params.put("code_challenge_method", method); }
        return "/oauth2/authorize?" + encode(params);
    }
    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return get(browser, path, "text/html");
    }
    private HttpResponse<String> get(HttpClient browser, String path, String accept) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", accept).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient browser, String path, Map<String, String> form, String basic) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded");
        if (basic != null) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (basic + ":" + AuthorizationFixture.CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)));
        return browser.send(request.POST(HttpRequest.BodyPublishers.ofString(encode(form))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String encode(Map<String, String> form) {
        return form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private static String challenge() throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }
    private static String code(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(REDIRECT).contains("code=");
        return Arrays.stream(URI.create(location).getRawQuery().split("&")).filter(p -> p.startsWith("code="))
                .map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
    private static void invalidGrant(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").asText()).isEqualTo("invalid_grant");
    }
    private static void assertOAuthError(HttpResponse<String> response) {
        assertThat(response.statusCode()).isIn(302, 400);
        if (response.statusCode() == 302) assertThat(response.headers().firstValue("Location").orElse("")).contains("error=").doesNotContain("code=");
    }
}
