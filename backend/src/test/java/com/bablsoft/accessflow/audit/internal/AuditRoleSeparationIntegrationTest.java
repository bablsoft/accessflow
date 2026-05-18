package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Verifies that the V38 grant pattern actually bites at the database layer: a non-superuser
 * Postgres role granted only {@code SELECT} on {@code audit_log} cannot UPDATE, DELETE,
 * TRUNCATE, or INSERT, even though {@code audit_log} is owned by a different role.
 *
 * <p>The Testcontainer init script (see {@link TestcontainersConfig}) provisions the
 * {@code accessflow_app} and {@code accessflow_audit} roles. Spring's primary connection
 * uses the testcontainer superuser; this test re-applies the migration's REVOKE / GRANT
 * pair against {@code accessflow_app} (the shared container means Flyway placeholders
 * may have already resolved against a different role for an earlier test class) and then
 * connects as {@code accessflow_app} to assert the resulting permission set.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditRoleSeparationIntegrationTest {

    private static final String APP_ROLE = "accessflow_app";
    private static final String APP_PASSWORD = "accessflow_app";
    private static final String AUDIT_ROLE = "accessflow_audit";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired DataSource primaryDataSource;
    @Autowired @Qualifier("auditDataSource") DataSource auditDataSource;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kp.getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeAll
    void applyMigrationGrantsToAppRole() throws SQLException {
        // V38 runs once per shared Postgres container and may have applied its REVOKE
        // against a different placeholder for an earlier test class. Re-assert the same
        // end state against the role this test class authenticates as.
        try (var conn = primaryDataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE audit_log OWNER TO " + AUDIT_ROLE);
            stmt.execute("REVOKE ALL ON audit_log FROM PUBLIC");
            stmt.execute("REVOKE ALL ON audit_log FROM " + APP_ROLE);
            stmt.execute("GRANT SELECT ON audit_log TO " + APP_ROLE);
        }
    }

    @Test
    void appRoleCannotDeleteFromAuditLog() throws SQLException {
        assertPermissionDeniedAsAppRole("DELETE FROM audit_log");
    }

    @Test
    void appRoleCannotUpdateAuditLog() throws SQLException {
        assertPermissionDeniedAsAppRole("UPDATE audit_log SET action = 'X' WHERE id IS NOT NULL");
    }

    @Test
    void appRoleCannotTruncateAuditLog() throws SQLException {
        assertPermissionDeniedAsAppRole("TRUNCATE TABLE audit_log");
    }

    @Test
    void appRoleCannotInsertIntoAuditLog() throws SQLException {
        assertPermissionDeniedAsAppRole(
                "INSERT INTO audit_log (id, organization_id, action, resource_type) "
                        + "VALUES ('00000000-0000-0000-0000-000000000000', "
                        + "'00000000-0000-0000-0000-000000000000', 'X', 'X')");
    }

    @Test
    void appRoleCanStillSelectFromAuditLog() throws SQLException {
        var url = ((HikariDataSource) primaryDataSource).getJdbcUrl();
        try (var conn = DriverManager.getConnection(url, APP_ROLE, APP_PASSWORD);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    void auditDataSourceUsesDedicatedRole() throws SQLException {
        try (var conn = auditDataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT current_user")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(AUDIT_ROLE);
        }
    }

    private void assertPermissionDeniedAsAppRole(String sql) throws SQLException {
        var url = ((HikariDataSource) primaryDataSource).getJdbcUrl();
        try (var conn = DriverManager.getConnection(url, APP_ROLE, APP_PASSWORD);
             var stmt = conn.createStatement()) {
            var thrown = catchThrowable(() -> stmt.execute(sql));
            assertThat(thrown)
                    .as("expected permission denied (SQLState 42501) for SQL: %s", sql)
                    .isInstanceOf(SQLException.class);
            assertThat(((SQLException) thrown).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }
}
