package com.bablsoft.accessflow.security.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.api.PasswordResetStatusType;
import com.bablsoft.accessflow.security.internal.persistence.entity.PasswordResetTokenEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class PasswordResetTokenRepositoryIntegrationTest {

    @Autowired PasswordResetTokenRepository repository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;

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
        repository.deleteAll();
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
    void findByTokenHash_returns_matching_row() {
        var saved = save("h1", PasswordResetStatusType.PENDING);

        assertThat(repository.findByTokenHash("h1"))
                .map(PasswordResetTokenEntity::getId).contains(saved.getId());
        assertThat(repository.findByTokenHash("missing")).isEmpty();
    }

    @Test
    void findFirstByUserIdAndStatus_returns_pending() {
        save("h1", PasswordResetStatusType.PENDING);
        save("h2", PasswordResetStatusType.USED);

        var pending = repository.findFirstByUserIdAndStatus(user.getId(),
                PasswordResetStatusType.PENDING);

        assertThat(pending).isPresent();
        assertThat(pending.get().getTokenHash()).isEqualTo("h1");
    }

    @Test
    void partial_unique_index_rejects_two_pending_rows_per_user() {
        save("h1", PasswordResetStatusType.PENDING);

        assertThatThrownBy(() -> {
            var second = newEntity("h2", PasswordResetStatusType.PENDING);
            repository.saveAndFlush(second);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void partial_unique_index_allows_pending_after_prior_revoked() {
        var first = save("h1", PasswordResetStatusType.PENDING);
        first.setStatus(PasswordResetStatusType.REVOKED);
        first.setRevokedAt(Instant.now());
        repository.saveAndFlush(first);

        var second = save("h2", PasswordResetStatusType.PENDING);
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    private PasswordResetTokenEntity save(String tokenHash, PasswordResetStatusType status) {
        return repository.saveAndFlush(newEntity(tokenHash, status));
    }

    private PasswordResetTokenEntity newEntity(String tokenHash, PasswordResetStatusType status) {
        var entity = new PasswordResetTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(user.getId());
        entity.setOrganizationId(organization.getId());
        entity.setTokenHash(tokenHash);
        entity.setStatus(status);
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
