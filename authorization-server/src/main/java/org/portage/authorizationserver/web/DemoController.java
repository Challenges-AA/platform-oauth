package org.portage.authorizationserver.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin public-client demonstration. It never receives a verifier or client secret. */
@RestController
public class DemoController {
    private final String redirectUri;

    public DemoController(@Value("${platform.authorization.demo-redirect-uri:http://127.0.0.1:9000/demo/callback}") String redirectUri) {
        this.redirectUri = redirectUri;
    }

    @GetMapping(value = {"/", "/demo/callback"}, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<Resource> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                        + "connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
                .body(new ClassPathResource("demo/index.html"));
    }

    @GetMapping(value = "/demo/config", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Configuration> configuration(Authentication principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Configuration("portage-public", redirectUri, "hello.read", principal.getName()));
    }

    public record Configuration(String clientId, String redirectUri, String scope, String subject) { }
}
