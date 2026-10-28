package org.portage.authorizationserver.crypto;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;

import java.net.InetSocketAddress;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteSigningConfigurationTest {
    /** Les erreurs réseau du signataire sont traduites en erreurs contrôlées et assainies. */
    @Test
    void rejectsMissingCredentialsAndSanitizesFailures() throws Exception {
        var config = new RemoteSigningConfiguration();
        assertThatThrownBy(() -> config.jwtEncoder("http://127.0.0.1", "short")).isInstanceOf(IllegalArgumentException.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/sign", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/oauth2/jwks", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            var encoder = config.jwtEncoder(url, "a".repeat(32));
            var claims = JwtClaimsSet.builder().subject("subject-1").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
            assertThatThrownBy(() -> encoder.encode(JwtEncoderParameters.from(claims)))
                    .isInstanceOf(JwtEncodingException.class).hasMessage("Signing service unavailable");
            assertThat(new PublicKeysController(url).keys().getStatusCode().value()).isEqualTo(503);
        } finally {
            server.stop(0);
        }
        assertThat(new PublicKeysController(url).keys().getStatusCode().value()).isEqualTo(503);
    }
}
