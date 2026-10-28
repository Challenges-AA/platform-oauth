package org.portage.keyservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * One writer per directory. Persist before publishing any new signing key.
 */
@Service
public class KeyStoreService implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KeyStoreService.class);
    private final Path file;
    private final SecretKeySpec master;
    private final long rotationSeconds, retentionSeconds;
    private final String issuer;
    private final Clock clock;
    private FileChannel lockChannel;
    private FileLock lock;
    private ECKey active;
    private Instant activated;
    private final List<Retired> retired = new ArrayList<>();

    private record Retired(ECKey key, Instant until) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KeyStoreService(@Value("${platform.keys.directory}") String directory,
                           @Value("${platform.keys.master-key}") String masterKey,
                           @Value("${platform.keys.rotation-seconds}") long rotationSeconds,
                           @Value("${platform.keys.retention-seconds}") long retentionSeconds,
                           @Value("${platform.keys.issuer}") String issuer) throws Exception {
        this(directory, masterKey, rotationSeconds, retentionSeconds, issuer, Clock.systemUTC());
    }

    KeyStoreService(String directory, String masterKey, long rotationSeconds, long retentionSeconds,
                    String issuer, Clock clock) throws Exception {
        byte[] secret = Base64.getDecoder().decode(masterKey);
        if (secret.length != 32 || rotationSeconds < 60 || retentionSeconds < 600)
            throw new IllegalArgumentException("Invalid key service configuration");
        this.master = new SecretKeySpec(secret, "AES");
        Arrays.fill(secret, (byte) 0);
        this.rotationSeconds = rotationSeconds;
        this.retentionSeconds = retentionSeconds;
        this.issuer = issuer;
        this.clock = clock;
        Path root = Path.of(directory).toAbsolutePath();
        Files.createDirectories(root);
        restrict(root, true);
        file = root.resolve("keys.enc");
        try {
            lockChannel = FileChannel.open(root.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IllegalStateException("Key directory already in use");
            if (Files.exists(file)) load();
            else {
                active = generate();
                activated = clock.instant();
                persist();
            }
        } catch (Exception e) {
            close();
            throw new IllegalStateException("Cannot open encrypted key store", e);
        }
    }

    private static ECKey generate() throws JOSEException {
        return new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.ES256).keyUse(KeyUse.SIGNATURE).generate();
    }

    public synchronized Map<String, Object> publicKeys() {
        List<JWK> keys = new ArrayList<>();
        keys.add(active.toPublicJWK());
        retired.stream().filter(r -> r.until().isAfter(clock.instant())).forEach(r -> keys.add(r.key()));
        return new JWKSet(keys).toJSONObject();
    }

    public synchronized String sign(Map<String, Object> claims) throws Exception {
        if (JSONObjectUtils.toJSONString(claims).length() > 8192) throw new IllegalArgumentException("Invalid claims");
        JWTClaimsSet parsed = JWTClaimsSet.parse(claims);
        Instant now = clock.instant();
        if (!issuer.equals(parsed.getIssuer()) || !List.of("portage-api").equals(parsed.getAudience())
                || parsed.getSubject() == null || parsed.getSubject().isBlank()
                || parsed.getJWTID() == null || parsed.getJWTID().isBlank()
                || !"hello.read".equals(parsed.getClaim("scope"))
                || parsed.getIssueTime() == null || parsed.getNotBeforeTime() == null || parsed.getExpirationTime() == null
                || parsed.getIssueTime().toInstant().isAfter(now.plusSeconds(5))
                || parsed.getIssueTime().toInstant().isBefore(now.minusSeconds(30))
                || parsed.getNotBeforeTime().after(parsed.getExpirationTime())
                || !parsed.getExpirationTime().toInstant().isAfter(now)
                || parsed.getExpirationTime().toInstant().isAfter(parsed.getIssueTime().toInstant().plusSeconds(300)))
            throw new IllegalArgumentException("Invalid claims");
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(active.getKeyID()).type(JOSEObjectType.JWT).build(), parsed);
        jwt.sign(new ECDSASigner(active));
        return jwt.serialize();
    }

    public synchronized Map<String, String> rotate() throws Exception {
        ECKey previous = active;
        Instant previousActivation = activated;
        List<Retired> previousRetired = new ArrayList<>(retired);
        active = generate();
        activated = clock.instant();
        retired.removeIf(r -> !r.until().isAfter(activated));
        retired.add(new Retired(previous.toPublicJWK(), activated.plusSeconds(retentionSeconds)));
        try {
            persist();
        } catch (Exception e) {
            active = previous;
            activated = previousActivation;
            retired.clear();
            retired.addAll(previousRetired);
            throw e;
        }
        LOG.info("Signing key rotated: kid={}, activatedAt={}", active.getKeyID(), activated);
        return Map.of("kid", active.getKeyID(), "activatedAt", activated.toString());
    }

    @Scheduled(fixedDelayString = "${platform.keys.check-interval-ms:30000}")
    public synchronized void rotateIfDue() throws Exception {
        if (!clock.instant().isBefore(activated.plusSeconds(rotationSeconds))) rotate();
    }

    private void persist() throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("active", active.toJSONObject());
        state.put("activated", activated.toString());
        state.put("retired", retired.stream().map(r -> Map.of("key", r.key().toJSONObject(), "until", r.until().toString())).toList());
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(JSONObjectUtils.toJSONString(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path temp = Files.createTempFile(file.getParent(), "store-", ".tmp");
        try {
            restrict(temp, false);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).flip();
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // Refuse une sauvegarde non atomique pour éviter un fichier de clés partiellement écrit.
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void load() throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 29) throw new IllegalStateException("Invalid encrypted store");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
        var state = JSONObjectUtils.parse(new String(cipher.doFinal(bytes, 12, bytes.length - 12), java.nio.charset.StandardCharsets.UTF_8));
        active = ECKey.parse(JSONObjectUtils.getJSONObject(state, "active"));
        if (!active.isPrivate() || !Curve.P_256.equals(active.getCurve()) || active.getKeyID() == null)
            throw new IllegalStateException("Invalid active key");
        activated = Instant.parse(JSONObjectUtils.getString(state, "activated"));
        for (Object value : JSONObjectUtils.getJSONArray(state, "retired")) {
            Map<String, Object> item = JSONObjectUtils.parse(JSONObjectUtils.toJSONString((Map<String, ?>) value));
            ECKey key = ECKey.parse(JSONObjectUtils.getJSONObject(item, "key"));
            retired.add(new Retired(key.toPublicJWK(), Instant.parse(JSONObjectUtils.getString(item, "until"))));
        }
    }

    private static void restrict(Path path, boolean directory) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    @Override
    @PreDestroy
    public synchronized void close() throws java.io.IOException {
        if (lock != null && lock.isValid()) lock.release();
        if (lockChannel != null) lockChannel.close();
    }
}
