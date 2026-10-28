package org.portage.keyservice;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyStoreServiceTest {
    final Path directory;

    /** Chaque test possède son propre stockage pour éviter les interférences. */
    KeyStoreServiceTest() throws Exception {
        directory = Files.createTempDirectory(Path.of("target"), "keys-test-");
    }

    @org.junit.jupiter.api.AfterEach
    /** Supprime le stockage temporaire créé par cette classe de test. */
    void cleanup() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private final String master = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    private final MutableClock clock = new MutableClock();

    /** Ouvre le magasin avec une horloge contrôlable afin de tester la rétention. */
    private KeyStoreService open() throws Exception {
        return new KeyStoreService(directory.toString(), master, 60, 600, "issuer", clock);
    }

    /** Claims conformes au contrat de signature utilisé dans les tests. */
    private Map<String, Object> claims() {
        return new HashMap<>(Map.of("iss", "issuer", "aud", List.of("portage-api"), "sub", "subject-1", "jti", UUID.randomUUID().toString(),
                "scope", "hello.read", "iat", clock.instant().getEpochSecond(), "nbf", clock.instant().getEpochSecond(),
                "exp", clock.instant().plusSeconds(300).getEpochSecond()));
    }

    @Test
    /** Couvre signature, vérification publique, rotation, persistance et expiration des anciennes clés. */
    void signsRotatesPersistsAndRetiresWithoutExposingPrivateMaterial() throws Exception {
        String firstKid, secondKid;
        try (var store = open()) {
            SignedJWT first = SignedJWT.parse(store.sign(claims()));
            firstKid = first.getHeader().getKeyID();
            ECKey publicKey = JWKSet.parse(store.publicKeys()).getKeyByKeyId(firstKid).toECKey();
            assertThat(first.verify(new ECDSAVerifier(publicKey))).isTrue();
            assertThat(publicKey.isPrivate()).isFalse();
            assertThat(new String(Files.readAllBytes(directory.resolve("keys.enc")), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("\"d\"");
            secondKid = store.rotate().get("kid");
            assertThat(secondKid).isNotEqualTo(firstKid);
            assertThat(SignedJWT.parse(store.sign(claims())).getHeader().getKeyID()).isEqualTo(secondKid);
            assertThat(JWKSet.parse(store.publicKeys()).getKeys()).hasSize(2);
            assertThatThrownBy(this::open).isInstanceOf(IllegalStateException.class);
        }
        try (var store = open()) {
            assertThat(SignedJWT.parse(store.sign(claims())).getHeader().getKeyID()).isEqualTo(secondKid);
            assertThat(JWKSet.parse(store.publicKeys()).getKeyByKeyId(firstKid)).isNotNull();
            clock.now = clock.now.plusSeconds(601);
            assertThat(JWKSet.parse(store.publicKeys()).getKeyByKeyId(firstKid)).isNull();
            store.rotateIfDue();
            assertThat(SignedJWT.parse(store.sign(claims())).getHeader().getKeyID()).isNotEqualTo(secondKid);
        }
    }

    @Test
    /** Un mauvais secret maître, un fichier altéré ou une configuration invalide bloque l'ouverture. */
    void rejectsWrongMasterCorruptionAndInvalidConfiguration() throws Exception {
        try (var store = open()) {
            store.rotateIfDue();
        }
        assertThatThrownBy(() -> new KeyStoreService(directory.toString(), Base64.getEncoder().encodeToString(new byte[32]),
                60, 600, "issuer", clock)).isInstanceOf(IllegalStateException.class);
        byte[] corrupted = Files.readAllBytes(directory.resolve("keys.enc"));
        corrupted[20] ^= 1;
        Files.write(directory.resolve("keys.enc"), corrupted);
        assertThatThrownBy(this::open).isInstanceOf(IllegalStateException.class);
        Files.write(directory.resolve("keys.enc"), new byte[1]);
        assertThatThrownBy(this::open).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyStoreService(directory.toString(), "AAAA", 60, 600, "issuer", clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyStoreService(directory.toString(), master, 1, 600, "issuer", clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyStoreService(directory.toString(), master, 60, 1, "issuer", clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    /** Refuse les claims manquants, trop anciens, expirés, excessifs ou hors du contrat. */
    void rejectsInvalidAndUnboundedClaims() throws Exception {
        try (var store = open()) {
            for (String name : List.of("iss", "aud", "sub", "jti", "scope", "iat", "nbf", "exp")) {
                var invalid = claims();
                invalid.remove(name);
                assertThatThrownBy(() -> store.sign(invalid)).isInstanceOf(Exception.class);
            }
            for (Map.Entry<String, Object> entry : Map.<String, Object>of("iss", "other", "scope", "admin", "sub", " ",
                    "jti", " ", "aud", List.of("other"), "iat", clock.instant().plusSeconds(60).getEpochSecond(),
                    "exp", clock.instant().plusSeconds(301).getEpochSecond(), "nbf", clock.instant().plusSeconds(400).getEpochSecond()).entrySet()) {
                var invalid = claims();
                invalid.put(entry.getKey(), entry.getValue());
                assertThatThrownBy(() -> store.sign(invalid)).isInstanceOf(IllegalArgumentException.class);
            }
            var oversized = claims();
            oversized.put("extra", "a".repeat(9000));
            assertThatThrownBy(() -> store.sign(oversized)).isInstanceOf(IllegalArgumentException.class);
            var expired = claims();
            expired.put("exp", clock.instant().minusSeconds(1).getEpochSecond());
            assertThatThrownBy(() -> store.sign(expired)).isInstanceOf(IllegalArgumentException.class);
            var old = claims();
            old.put("iat", clock.instant().minusSeconds(31).getEpochSecond());
            assertThatThrownBy(() -> store.sign(old)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    /** Une écriture ratée ne doit pas remplacer la clé active utilisable en mémoire. */
    void failedPersistenceLeavesPreviousKeyUsable() throws Exception {
        try (var store = open()) {
            String original = SignedJWT.parse(store.sign(claims())).getHeader().getKeyID();
            Path backup = directory.resolve("backup.enc");
            Files.move(directory.resolve("keys.enc"), backup);
            Files.createDirectory(directory.resolve("keys.enc"));
            Files.writeString(directory.resolve("keys.enc/blocker"), "prevent replacement");
            assertThatThrownBy(store::rotate).isInstanceOf(Exception.class);
            assertThat(SignedJWT.parse(store.sign(claims())).getHeader().getKeyID()).isEqualTo(original);
            assertThat(JWKSet.parse(store.publicKeys()).getKeys()).hasSize(1);
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-27T12:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}
