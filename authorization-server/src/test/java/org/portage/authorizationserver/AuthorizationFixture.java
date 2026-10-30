package org.portage.authorizationserver;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Prépare des identifiants isolés et un vrai Key Service éphémère pour tester
 * l'intégration HTTP de l'Authorization Server sans secrets fixes dans le dépôt.
 */
public final class AuthorizationFixture {
    public static final String CLIENT_SECRET = UUID.randomUUID().toString();
    private static final String STORAGE_KEY = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    private static final String AUTH_DIRECTORY = "target/test-auth-" + UUID.randomUUID();
    private static final List<String> PASSWORDS = java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID().toString()).toList();
    private static final Path KEYS = Path.of("target", "test-runtime-keys-" + UUID.randomUUID()).toAbsolutePath();
    private static final String SIGNING_SECRET = UUID.randomUUID().toString();
    private static final org.springframework.context.ConfigurableApplicationContext KEY_SERVICE =
            new org.springframework.boot.builder.SpringApplicationBuilder(org.portage.keyservice.KeyServiceApplication.class)
                    .run("--server.port=0", "--spring.application.name=test-key-service",
                            "--platform.keys.directory=" + KEYS,
                            "--platform.keys.master-key=" + Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)),
                            "--platform.keys.signing-secret=" + SIGNING_SECRET,
                            "--platform.keys.admin-secret=" + UUID.randomUUID(),
                            "--platform.keys.rotation-seconds=86400", "--platform.keys.retention-seconds=900",
                            "--platform.keys.issuer=http://127.0.0.1:9000");

    private AuthorizationFixture() {
    }

    /** Injecte les valeurs de test dans Spring sans modifier les fichiers de configuration. */
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.authorization.storage-key", () -> STORAGE_KEY);
        registry.add("platform.authorization.directory", () -> AUTH_DIRECTORY);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
        registry.add("platform.client.secret", () -> CLIENT_SECRET);
        registry.add("platform.signing.secret", () -> SIGNING_SECRET);
        registry.add("platform.signing.url", () -> "http://127.0.0.1:" + KEY_SERVICE.getEnvironment().getProperty("local.server.port"));
        for (int i = 0; i < 5; i++) {
            int index = i;
            registry.add("platform.users[" + i + "].username", () -> "user" + (index + 1));
            registry.add("platform.users[" + i + "].password", () -> PASSWORDS.get(index));
            registry.add("platform.users[" + i + "].subject", () -> "subject-" + (index + 1));
        }
    }

    public static String password() {
        return PASSWORDS.getFirst();
    }

    /** Envoie un formulaire OAuth à un port de test avec authentification client Basic. */
    public static HttpResponse<String> request(int port, String client, String secret, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        String basic = Base64.getEncoder().encodeToString((client + ":" + secret).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Encode les champs du formulaire selon application/x-www-form-urlencoded. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Le contexte Spring partagé ferme le service isolé via son arrêt normal. */
    public static void cleanup() throws java.io.IOException {
        // Shared with Cucumber contexts; Spring's shutdown hook closes the isolated key service.
    }
}
