package com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips the two column types Hibernate cannot validate from the mapping alone — the
 * {@code service_account_source} PG enum and the {@code text[]} allow-list, whose NULL-vs-empty
 * distinction carries meaning (#872) — and proves the schema-level cascade from {@code users}.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ServiceAccountRepositoryIntegrationTest {

    @Autowired ServiceAccountRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID organizationId;

    @BeforeEach
    void seedOrganization() {
        organizationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                organizationId, "sa-repo", "sa-repo-" + organizationId);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", organizationId);
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organizationId);
    }

    @Test
    void roundTripsEnumAndArrayAndDistinguishesNullFromEmptyAllowList() {
        var unrestricted = seedUser();
        var locked = seedUser();
        var narrowed = seedUser();
        repository.save(account(unrestricted, ServiceAccountSource.BOOTSTRAP, null));
        repository.save(account(locked, ServiceAccountSource.UI, new String[0]));
        var full = account(narrowed, ServiceAccountSource.UI, new String[] {"validate_sql", "list_datasources"});
        full.setDescription("narrowed");
        full.setOwnerUserId(unrestricted);
        full.setRateLimitPerMinute(30);
        full.setRateLimitPerDay(1000);
        repository.save(full);

        assertThat(repository.findById(unrestricted)).get().satisfies(e -> {
            assertThat(e.getManagedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
            assertThat(e.getMcpToolAllowList()).isNull();
        });
        assertThat(repository.findById(locked)).get().satisfies(e -> {
            assertThat(e.getManagedBy()).isEqualTo(ServiceAccountSource.UI);
            assertThat(e.getMcpToolAllowList()).isEmpty();
        });
        assertThat(repository.findById(narrowed)).get().satisfies(e -> {
            assertThat(e.getMcpToolAllowList()).containsExactly("validate_sql", "list_datasources");
            assertThat(e.getOwnerUserId()).isEqualTo(unrestricted);
            assertThat(e.getRateLimitPerMinute()).isEqualTo(30);
            assertThat(e.getRateLimitPerDay()).isEqualTo(1000);
            assertThat(e.getDescription()).isEqualTo("narrowed");
        });
        assertThat(repository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId))
                .extracting(ServiceAccountEntity::getUserId)
                .containsExactly(unrestricted, locked, narrowed);
    }

    @Test
    void deletingTheUserRemovesTheDetailRowAndDeletingTheOwnerOnlyDetaches() {
        var owner = seedUser();
        var account = seedUser();
        var detail = account(account, ServiceAccountSource.UI, null);
        detail.setOwnerUserId(owner);
        repository.save(detail);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", owner);
        assertThat(repository.findById(account)).get()
                .extracting(ServiceAccountEntity::getOwnerUserId).isNull();

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", account);
        assertThat(repository.findById(account)).isEmpty();
    }

    private ServiceAccountEntity account(UUID userId, ServiceAccountSource source, String[] allowList) {
        var entity = new ServiceAccountEntity();
        entity.setUserId(userId);
        entity.setOrganizationId(organizationId);
        entity.setManagedBy(source);
        entity.setMcpToolAllowList(allowList);
        return entity;
    }

    private UUID seedUser() {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, organization_id, email, role) "
                        + "VALUES (?, ?, ?, 'ADMIN'::user_role_type)",
                id, organizationId, id + "@sa-repo.example.com");
        return id;
    }
}
