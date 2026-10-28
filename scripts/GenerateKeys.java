import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.Base64;

/**
 * Générateur local de JWK ES256 pour l'ancienne démo. Les arguments sont des chemins ;
 * le matériel privé n'est jamais affiché.
 */
public final class GenerateKeys {
    /** Encode une coordonnée EC en Base64URL sans padding et sur 32 octets. */
    private static String b64(BigInteger n, int size) {
        byte[] raw = n.toByteArray(), out = new byte[size];
        System.arraycopy(raw, Math.max(0, raw.length - size), out, Math.max(0, size - raw.length), Math.min(size, raw.length));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    /** Ancien utilitaire local de démonstration ; le Key Service courant génère ses propres clés. */
    public static void main(String[] a) throws Exception {
        if (a.length != 2) throw new IllegalArgumentException("Expected output paths");
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 2; i++) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), random);
            KeyPair kp = g.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            ECPrivateKey pri = (ECPrivateKey) kp.getPrivate();
            String kid = i == 0 ? "active" : "next";
            String jwk = "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"" + kid + "\",\"x\":\"" + b64(pub.getW().getAffineX(), 32) + "\",\"y\":\"" + b64(pub.getW().getAffineY(), 32) + "\",\"d\":\"" + b64(pri.getS(), 32) + "\"}";
            Path p = Path.of(a[i]);
            Files.createDirectories(p.toAbsolutePath().getParent());
            Files.writeString(p, jwk, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }
}
