package com.bablsoft.accessflow.core.internal.persistence;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V199 (#1085): {@code row_limit_override} on both grant tables is NULL or at least 1 at the
 * database, and the migration's own normalisation turns a pre-existing non-positive value into
 * NULL so the constraint can be added on a dirty table.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class RowLimitOverrideConstraintIntegrationTest {

    private static final String MIGRATION = "db/migration/V199__row_limit_override_positive.sql";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity org;
    private UserEntity user;
    private DatasourceEntity datasource;
    private UUID groupId;

    @BeforeEach
    void seed() {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("RowLimit " + suffix, "row-limit-" + suffix);
        user = saveUser("row-limit-" + suffix + "@example.com");
        datasource = saveDatasource("RowLimit-DS-" + suffix);
        groupId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO user_groups (id, organization_id, name) VALUES (?, ?, ?)",
                groupId, org.getId(), "row-limit-" + suffix);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM datasource_user_permissions WHERE datasource_id = ?",
                datasource.getId());
        jdbcTemplate.update("DELETE FROM datasource_group_permissions WHERE datasource_id = ?",
                datasource.getId());
        jdbcTemplate.update("DELETE FROM user_groups WHERE id = ?", groupId);
        datasourceRepository.deleteById(datasource.getId());
        userRepository.deleteById(user.getId());
        organizationRepository.deleteById(org.getId());
    }

    @Test
    void userGrantRejectsANonPositiveRowLimitOnInsertAndUpdate() {
        assertThatThrownBy(() -> insertUserGrant(0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_dup_row_limit_override_positive");
        assertThatThrownBy(() -> insertUserGrant(-1))
                .isInstanceOf(DataIntegrityViolationException.class);

        var id = insertUserGrant(null);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE datasource_user_permissions SET row_limit_override = 0 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update(
                "UPDATE datasource_user_permissions SET row_limit_override = 1 WHERE id = ?", id);
        assertThat(rowLimitOf("datasource_user_permissions", id)).isEqualTo(1);
    }

    @Test
    void groupGrantRejectsANonPositiveRowLimitOnInsertAndUpdate() {
        assertThatThrownBy(() -> insertGroupGrant(0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_dgp_row_limit_override_positive");

        var id = insertGroupGrant(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE datasource_group_permissions SET row_limit_override = -5 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update(
                "UPDATE datasource_group_permissions SET row_limit_override = NULL WHERE id = ?", id);
        assertThat(rowLimitOf("datasource_group_permissions", id)).isNull();
    }

    @Test
    void migrationNormalisesExistingNonPositiveValuesBeforeAddingTheConstraint() throws IOException {
        var migration = new String(new ClassPathResource(MIGRATION).getContentAsByteArray(),
                StandardCharsets.UTF_8);
        var tx = new TransactionTemplate(transactionManager);
        // Rolled back: drop the constraints to recreate the pre-V199 shape, seed dirty rows, and
        // replay the shipped migration verbatim — it must succeed and leave the rows NULL.
        tx.executeWithoutResult(status -> {
            jdbcTemplate.execute("""
                    ALTER TABLE datasource_user_permissions
                        DROP CONSTRAINT chk_dup_row_limit_override_positive;
                    ALTER TABLE datasource_group_permissions
                        DROP CONSTRAINT chk_dgp_row_limit_override_positive;
                    """);
            var userGrant = insertUserGrant(0);
            var groupGrant = insertGroupGrant(-3);

            jdbcTemplate.execute(migration);

            assertThat(rowLimitOf("datasource_user_permissions", userGrant)).isNull();
            assertThat(rowLimitOf("datasource_group_permissions", groupGrant)).isNull();
            status.setRollbackOnly();
        });

        assertThatThrownBy(() -> insertUserGrant(0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUserGrant(Integer rowLimit) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO datasource_user_permissions (id, datasource_id, user_id, can_read,
                    row_limit_override, created_by)
                VALUES (?, ?, ?, true, ?, ?)
                """, id, datasource.getId(), user.getId(), rowLimit, user.getId());
        return id;
    }

    private UUID insertGroupGrant(Integer rowLimit) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO datasource_group_permissions (id, organization_id, datasource_id,
                    group_id, can_read, row_limit_override, created_by)
                VALUES (?, ?, ?, ?, true, ?, ?)
                """, id, org.getId(), datasource.getId(), groupId, rowLimit, user.getId());
        return id;
    }

    private Integer rowLimitOf(String table, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT row_limit_override FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(slug);
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String email) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(email);
        entity.setDisplayName(email);
        entity.setPasswordHash("not-a-real-hash");
        entity.setRole(UserRoleType.ADMIN);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    private DatasourceEntity saveDatasource(String name) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName(name);
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("seed-password"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(10);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }
}
