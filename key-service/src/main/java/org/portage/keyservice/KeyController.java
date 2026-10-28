package org.portage.keyservice;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class KeyController {
    private final KeyStoreService keys;

    public KeyController(KeyStoreService keys) {
        this.keys = keys;
    }

    @GetMapping("/oauth2/jwks")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(keys.publicKeys());
    }

    @PostMapping("/internal/sign")
    public ResponseEntity<Map<String, String>> sign(@RequestBody Map<String, Object> claims) throws Exception {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("token", keys.sign(claims)));
    }

    @PostMapping("/internal/rotate")
    public Map<String, String> rotate() throws Exception {
        return keys.rotate();
    }

    @ExceptionHandler({IllegalArgumentException.class, java.text.ParseException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_claims"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "key_service_unavailable"));
    }
}
