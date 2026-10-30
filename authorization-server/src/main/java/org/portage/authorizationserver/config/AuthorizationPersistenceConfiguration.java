package org.portage.authorizationserver.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.transaction.support.TransactionTemplate;
import org.portage.authorizationserver.security.RotatingAuthorizationService;

/** Encrypted, persistent single-node store for authorizations, consents and browser sessions. */
@Configuration
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 1800)
public class AuthorizationPersistenceConfiguration {
    @Bean
    org.springframework.session.web.http.DefaultCookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookie = new org.springframework.session.web.http.DefaultCookieSerializer();
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        return cookie;
    }
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(@Value("${platform.authorization.directory:runtime-auth}") String directory,
                               @Value("${platform.authorization.storage-key}") String key) throws Exception {
        try {
            if (Base64.getDecoder().decode(key).length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OAUTH_STORAGE_KEY must be Base64 encoding exactly 32 random bytes");
        }
        Path path = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(path);
        var source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:file:" + path.resolve("authorization").toString().replace('\\', '/')
                + ";CIPHER=AES;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=15000");
        source.setUsername("sa");
        source.setPassword(key + " " + key);
        source.setMaximumPoolSize(10);
        // Spring's schemas, made idempotent; never drop live authorizations or sessions on restart.
        for (String schema : new String[]{
                "org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql",
                "org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql",
                "org/springframework/session/jdbc/schema-h2.sql"}) {
            String sql = new ClassPathResource(schema).getContentAsString(StandardCharsets.UTF_8)
                    .replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
                    .replace("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
                    .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
        }
        new ResourceDatabasePopulator(new ClassPathResource("authorization-lifecycle.sql")).execute(source);
        return source;
    }

    @Bean
    TransactionTemplate authorizationTransactions(DataSource source) {
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Bean
    RotatingAuthorizationService authorizationService(DataSource source, RegisteredClientRepository clients,
                                                       TransactionTemplate transactions) {
        return new RotatingAuthorizationService(new JdbcTemplate(source), clients, transactions);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(DataSource source, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(new JdbcTemplate(source), clients);
    }
}
