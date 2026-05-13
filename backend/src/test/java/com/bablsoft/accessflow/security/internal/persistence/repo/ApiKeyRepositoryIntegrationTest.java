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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
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

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var pk = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key",
                () -> "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

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

    private ApiKeyEntity save(String name, Instant createdAt) {
        var entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setUserId(user.getId());
        entity.setName(name);
        entity.setKeyPrefix("af_demo" + name);
        entity.setKeyHash("hash-" + UUID.randomUUID());
        entity.setCreatedAt(createdAt);
        return apiKeyRepository.save(entity);
    }
}
