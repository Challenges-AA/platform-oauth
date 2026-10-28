package org.portage.resourceserver;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class JwksFixture {
    static final KeyPair KEY_A = keyPair();
    static final KeyPair KEY_B = keyPair();
    static final AtomicReference<String> jwks = new AtomicReference<>();
    static final HttpServer server = startServer();

    private JwksFixture() {
    }

    /** Adresse du serveur JWKS local consulté par le décodeur de jetons de test. */
    static String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    /** Publie les clés publiques sélectionnées pour simuler le JWKS actif et suivant. */
    static void publish(String... kids) {
        List<com.nimbusds.jose.jwk.JWK> keys = java.util.Arrays.stream(kids).<com.nimbusds.jose.jwk.JWK>map(kid -> ecKey(kid, kid.equals("key-b") ? KEY_B : KEY_A)).toList();
        jwks.set(new JWKSet(keys).toString());
    }

    /** Construit un jeton signé dont les claims sont paramétrables pour les cas limites. */
    static String token(String kid, KeyPair pair, String scope, long issuedOffset, long expiresOffset,
                        long notBeforeOffset, String issuer, String audience, String algorithm) throws Exception {
        JWSAlgorithm alg = new JWSAlgorithm(algorithm);
        JWSHeader header = new JWSHeader.Builder(alg).keyID(kid).type(JOSEObjectType.JWT).build();
        long now = System.currentTimeMillis() / 1000;
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("subject-1")
                .audience(audience).issueTime(new Date((now + issuedOffset) * 1000))
                .notBeforeTime(new Date((now + notBeforeOffset) * 1000))
                .expirationTime(new Date((now + expiresOffset) * 1000)).jwtID("test-id")
                .claim("scope", scope).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new ECDSASigner((ECPrivateKey) pair.getPrivate()));
        return jwt.serialize();
    }

    /** Jeton correctement signé mais auquel manque le claim nbf obligatoire. */
    static String tokenWithoutNotBefore() {
        try {
            long now = System.currentTimeMillis() / 1000;
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("http://127.0.0.1:9000").subject("subject-1")
                    .audience("portage-api").issueTime(new Date(now * 1000)).expirationTime(new Date((now + 300) * 1000))
                    .jwtID("missing-nbf").claim("scope", "hello.read").build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("key-a").type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new ECDSASigner((ECPrivateKey) KEY_A.getPrivate()));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Jeton non signé destiné à vérifier que l'algorithme none est refusé. */
    static String unsignedToken() {
        long now = System.currentTimeMillis() / 1000;
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("http://127.0.0.1:9000").subject("subject-1")
                .audience("portage-api").issueTime(new Date(now * 1000)).notBeforeTime(new Date((now - 1) * 1000))
                .expirationTime(new Date((now + 300) * 1000)).jwtID("unsigned").claim("scope", "hello.read").build();
        return new PlainJWT(new PlainHeader.Builder().build(), claims).serialize();
    }

    /** Altère l'algorithme annoncé sans recalculer la signature. */
    static String withUnexpectedAlgorithm(String signedToken) {
        String[] parts = signedToken.split("\\.");
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"ES384\",\"kid\":\"key-a\",\"typ\":\"JWT\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return header + "." + parts[1] + "." + parts[2];
    }

    /** Associe un identifiant kid à la partie publique d'une paire EC de test. */
    private static ECKey ecKey(String kid, KeyPair pair) {
        return new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic()).keyID(kid).build();
    }

    /** Génère une paire de clés elliptique utilisée uniquement par les tests. */
    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Démarre un mini serveur HTTP qui expose le document JWKS courant. */
    private static HttpServer startServer() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/jwks", exchange -> {
                byte[] body = jwks.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            publish("key-a");
            http.start();
            return http;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
