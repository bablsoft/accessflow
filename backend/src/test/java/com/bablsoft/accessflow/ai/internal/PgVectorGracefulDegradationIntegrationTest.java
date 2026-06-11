package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.NoPgVectorTestcontainersConfig;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the application boots against a Postgres without the {@code vector} extension (AF-336):
 * {@code PgVectorFlywayConfiguration} skips V69, {@code knowledge_document} is created by V73 (so
 * Hibernate {@code ddl-auto=validate} passes), {@code vector_store} is omitted, and
 * {@link PgVectorAvailability} reports unavailable. A regression guard for the original
 * {@code type "vector" does not exist} startup crash.
 */
@SpringBootTest
@ImportTestcontainers(NoPgVectorTestcontainersConfig.class)
class PgVectorGracefulDegradationIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PgVectorAvailability pgVectorAvailability;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @Test
    void contextStartsWithPgVectorUnavailable() {
        assertThat(pgVectorAvailability.isAvailable()).isFalse();
    }

    @Test
    void vectorExtensionAndStoreAreAbsentButKnowledgeDocumentExists() {
        assertThat(extensionCount("vector")).isZero();
        assertThat(tableExists("knowledge_document")).isTrue();
        assertThat(tableExists("vector_store")).isFalse();
    }

    @Test
    void vectorStoreMigrationIsRecordedAsAppliedAndLaterMigrationsRan() {
        assertThat(migrationApplied("69")).isTrue();
        assertThat(migrationApplied("73")).isTrue();
    }

    private boolean tableExists(String name) {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    private int extensionCount(String name) {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    private boolean migrationApplied(String version) {
        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success = true",
                Integer.class, version);
        return count != null && count > 0;
    }
}
