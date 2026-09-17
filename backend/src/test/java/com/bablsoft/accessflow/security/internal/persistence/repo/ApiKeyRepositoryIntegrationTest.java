package com.bablsoft.accessflow.security.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.persistence.entity.ApiKeyEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiKeyRepositoryIntegrationTest {

    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private OrganizationEntity organization;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        apiKeyRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Acme");
        organization.setSlug("acme-" + UUID.randomUUID());
        organizationRepository.save(organization);

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("User");
        user.setPasswordHash("hashed");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        userRepository.save(user);
    }

    @Test
    void persists_and_finds_by_user_in_created_at_desc_order() {
        var older = save("older", Instant.parse("2026-05-01T10:00:00Z"));
        var newer = save("newer", Instant.parse("2026-05-05T10:00:00Z"));

        var rows = apiKeyRepository.findByUserIdOrderByCreatedAtDesc(user.getId());

        assertThat(rows).extracting(ApiKeyEntity::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void existsByUserIdAndName_detects_duplicate() {
        save("ci", Instant.now());
        assertThat(apiKeyRepository.existsByUserIdAndName(user.getId(), "ci")).isTrue();
        assertThat(apiKeyRepository.existsByUserIdAndName(user.getId(), "other")).isFalse();
    }

    @Test
    void findByKeyHash_returns_matching_row() {
        var saved = save("ci", Instant.now());
        assertThat(apiKeyRepository.findByKeyHash(saved.getKeyHash()))
                .map(ApiKeyEntity::getId).contains(saved.getId());
        assertThat(apiKeyRepository.findByKeyHash("no-such-hash")).isEmpty();
    }

    @Test
    void touchLastUsedAt_updates_only_the_target_row() {
        var a = save("a", Instant.now());
        save("b", Instant.now());
        var instant = Instant.parse("2026-05-10T00:00:00Z");
        // Run the @Modifying update in its own transaction so it commits before we read.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                apiKeyRepository.touchLastUsedAt(a.getId(), instant));
        var refreshed = apiKeyRepository.findById(a.getId()).orElseThrow();
        assertThat(refreshed.getLastUsedAt()).isEqualTo(instant);
    }

    @Test
    void findByUserIdIn_returns_every_users_keys_newest_first() {
        var other = new UserEntity();
        other.setId(UUID.randomUUID());
        other.setEmail("other-" + UUID.randomUUID() + "@example.com");
        other.setDisplayName("Other");
        other.setPasswordHash("hashed");
        other.setRole(UserRoleType.ANALYST);
        other.setAuthProvider(AuthProviderType.LOCAL);
        other.setActive(true);
        other.setOrganization(organization);
        userRepository.save(other);
        var mineOld = save("old", Instant.parse("2026-05-01T10:00:00Z"));
        var mineNew = save("new", Instant.parse("2026-05-05T10:00:00Z"));
        var theirs = save(other, "theirs", Instant.parse("2026-05-03T10:00:00Z"));

        var rows = apiKeyRepository.findByUserIdInOrderByCreatedAtDesc(List.of(user.getId(), other.getId()));

        assertThat(rows).extracting(ApiKeyEntity::getId)
                .containsExactly(mineNew.getId(), theirs.getId(), mineOld.getId());
        assertThat(apiKeyRepository.findByUserIdInOrderByCreatedAtDesc(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void clearBootstrapDeclaredForOtherKeys_demotes_only_the_users_other_flagged_rows() {
        var kept = save("kept", Instant.now());
        var demoted = save("demoted", Instant.now());
        var plain = save("plain", Instant.now());
        kept.setBootstrapDeclared(true);
        demoted.setBootstrapDeclared(true);
        apiKeyRepository.saveAll(List.of(kept, demoted));

        var changed = new TransactionTemplate(transactionManager).execute(status ->
                apiKeyRepository.clearBootstrapDeclaredForOtherKeys(user.getId(), kept.getId()));

        assertThat(changed).isEqualTo(1);
        assertThat(apiKeyRepository.findById(kept.getId()).orElseThrow().isBootstrapDeclared()).isTrue();
        assertThat(apiKeyRepository.findById(demoted.getId()).orElseThrow().isBootstrapDeclared()).isFalse();
        assertThat(apiKeyRepository.findById(plain.getId()).orElseThrow().isBootstrapDeclared()).isFalse();
    }

    @Test
    void bootstrap_declared_defaults_to_false() {
        var saved = save("ci", Instant.now());
        assertThat(apiKeyRepository.findById(saved.getId()).orElseThrow().isBootstrapDeclared()).isFalse();
    }

    private ApiKeyEntity save(String name, Instant createdAt) {
        return save(user, name, createdAt);
    }

    private ApiKeyEntity save(UserEntity owner, String name, Instant createdAt) {
        var entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setUserId(owner.getId());
        entity.setName(name);
        entity.setKeyPrefix("af_demo" + name);
        entity.setKeyHash("hash-" + UUID.randomUUID());
        entity.setCreatedAt(createdAt);
        return apiKeyRepository.save(entity);
    }
}
