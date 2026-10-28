package org.portage.authorizationserver.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Preserve the issuer's JWKS URL; only public material crosses this boundary.
 */
@RestController
public class PublicKeysController {
    private final String url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public PublicKeysController(@Value("${platform.signing.url}") String url) {
        this.url = url;
    }

    @GetMapping(value = "/oauth2/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> keys() {
        try {
            var response = client.send(HttpRequest.newBuilder(URI.create(url + "/oauth2/jwks"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200)
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Ne pas exposer l'URL interne, les détails réseau ou la réponse du service en erreur.
        }
        return ResponseEntity.status(503).body("{\"error\":\"key_service_unavailable\"}");
    }
}
