import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * Signataire local de l'ancienne démonstration. Le JWT est consommé par demo.py
 * et n'est jamais écrit dans les logs.
 */
public final class SignNextToken {
    /** Lit un champ chaîne simple dans le JWK du scénario de démonstration historique. */
    static String field(String s, String key) {
        Matcher m = Pattern.compile("\\\"" + key + "\\\":\\\"([^\\\"]+)\\\"").matcher(s);
        if (!m.find()) throw new IllegalArgumentException();
        return m.group(1);
    }

    /** Décode une valeur Base64URL du JWK. */
    static byte[] unb64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    /** Convertit les octets décodés en entier non signé pour reconstruire les paramètres EC. */
    static BigInteger bi(String s) {
        return new BigInteger(1, unb64(s));
    }

    /** Ancien signataire local de démonstration ; la signature courante passe par le Key Service. */
    public static void main(String[] a) throws Exception {
        if (a.length < 1 || a.length > 2)
            throw new IllegalArgumentException("Expected key path and optional header key id");
        String jwk = Files.readString(Path.of(a[0]));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ec = params.getParameterSpec(ECParameterSpec.class);
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(bi(field(jwk, "d")), ec));
        long now = Instant.now().getEpochSecond();
        String kid = a.length == 2 ? a[1] : field(jwk, "kid");
        String h = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"alg\":\"ES256\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String c = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"iss\":\"http://127.0.0.1:9000\",\"sub\":\"subject-1\",\"aud\":\"portage-api\",\"iat\":" + now + ",\"nbf\":" + now + ",\"exp\":" + (now + 300) + ",\"jti\":\"demo-next-" + UUID.randomUUID() + "\",\"scope\":\"hello.read\"}").getBytes(StandardCharsets.UTF_8));
        String data = h + "." + c;
        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initSign(key);
        sig.update(data.getBytes(StandardCharsets.US_ASCII));
        System.out.print(data + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign()));
    }
}
