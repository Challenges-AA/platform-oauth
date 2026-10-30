package org.portage.authorizationserver;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real login and authorization for the BDD scenarios. Never follows the callback. */
public final class PkceBrowser {
    private static final String CALLBACK = "http://127.0.0.1:8080/callback";
    private static final String VERIFIER = "a".repeat(64);
    private PkceBrowser() {}

    public static HttpResponse<String> exchange(int port, boolean validVerifier, boolean knownClient) throws Exception {
        String base = "http://127.0.0.1:" + port;
        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var page = browser.send(HttpRequest.newBuilder(URI.create(base + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var csrf = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        assertThat(csrf.find()).isTrue();
        var login = browser.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(Map.of("username", "user1", "password", AuthorizationFixture.password(), "_csrf", csrf.group(1))))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
        String state = UUID.randomUUID().toString();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var authorize = browser.send(HttpRequest.newBuilder(URI.create(base + "/oauth2/authorize?" + encode(Map.of(
                "response_type", "code", "client_id", "portage-public", "redirect_uri", CALLBACK,
                "scope", "hello.read", "state", state, "code_challenge", challenge, "code_challenge_method", "S256"))))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(authorize.statusCode()).isEqualTo(302);
        var callback = URI.create(authorize.headers().firstValue("Location").orElseThrow());
        assertThat(callback.toString()).startsWith(CALLBACK + "?");
        var params = new HashMap<String, String>();
        for (String pair : callback.getRawQuery().split("&")) {
            String[] fields = pair.split("=", 2);
            params.put(fields[0], URLDecoder.decode(fields[1], StandardCharsets.UTF_8));
        }
        assertThat(params.get("state")).isEqualTo(state);
        var form = Map.of("grant_type", "authorization_code", "client_id", "portage-public", "code", params.get("code"),
                "redirect_uri", CALLBACK, "code_verifier", validVerifier ? VERIFIER : "b".repeat(64));
        var request = HttpRequest.newBuilder(URI.create(base + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (!knownClient) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("unknown:wrong".getBytes(StandardCharsets.UTF_8)));
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(encode(form))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(Map<String,String> form) {
        return form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
}
