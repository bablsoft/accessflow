package com.bablsoft.accessflow.lifecycle.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the repository's JPQL against a real Postgres — guards against Hibernate rendering the
 * {@code status} predicate with a cast to the Java enum class name instead of the
 * {@code erasure_status} Postgres enum type (#546).
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@ImportTestcontainers(TestcontainersConfig.class)
class DeletionRequestRepositoryIntegrationTest {

    @Autowired DeletionRequestRepository repository;

    private static final Instant CUTOFF = Instant.parse("2026-06-22T00:00:00Z");

    /**
     * The lifecycle scheduled jobs (e.g. {@code ErasureReviewTimeoutJob}) run in the full context and
     * would otherwise select and mutate the rows this test seeds — bumping their {@code @Version} and
     * flaking the version-checked cleanup delete. A lock provider that never acquires the lock makes
     * ShedLock skip every {@code @Scheduled} job for the duration of this test.
     */
    @TestConfiguration
    static class NoScheduledJobsConfig {
        @Bean("lockProvider")
        @Primary
        LockProvider neverAcquiresLockProvider() {
            return (LockConfiguration lockConfig) -> Optional.empty();
        }
    }

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pk = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void findTimedOutPendingReviewIds_returnsPendingReviewRowsPastCutoff() {
        var timedOut = repository.save(
                newRequest(ErasureStatus.PENDING_REVIEW, CUTOFF.minusSeconds(60)));

        var ids = repository.findTimedOutPendingReviewIds(ErasureStatus.PENDING_REVIEW, CUTOFF);

        assertThat(ids).containsExactly(timedOut.getId());
    }

    @Test
    void findTimedOutPendingReviewIds_excludesRowsUpdatedAfterCutoff() {
        repository.save(newRequest(ErasureStatus.PENDING_REVIEW, CUTOFF.plusSeconds(60)));

        var ids = repository.findTimedOutPendingReviewIds(ErasureStatus.PENDING_REVIEW, CUTOFF);

        assertThat(ids).isEmpty();
    }

    @Test
    void findTimedOutPendingReviewIds_excludesOtherStatuses() {
        repository.save(newRequest(ErasureStatus.APPROVED, CUTOFF.minusSeconds(60)));
        repository.save(newRequest(ErasureStatus.PENDING_SCOPE_AI, CUTOFF.minusSeconds(60)));

        var ids = repository.findTimedOutPendingReviewIds(ErasureStatus.PENDING_REVIEW, CUTOFF);

        assertThat(ids).isEmpty();
    }

    @Test
    void findIdsByStatus_filtersByStatus() {
        var approved = repository.save(newRequest(ErasureStatus.APPROVED, CUTOFF));
        repository.save(newRequest(ErasureStatus.PENDING_REVIEW, CUTOFF));

        var ids = repository.findIdsByStatus(ErasureStatus.APPROVED);

        assertThat(ids).containsExactly(approved.getId());
    }

    private DeletionRequestEntity newRequest(ErasureStatus status, Instant updatedAt) {
        var entity = new DeletionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setDatasourceId(UUID.randomUUID());
        entity.setSubjectType(LifecycleSubjectType.EMAIL);
        entity.setSubjectIdentifier("subject@example.com");
        entity.setTargetColumns(new String[0]);
        entity.setStatus(status);
        entity.setRequestedBy(UUID.randomUUID());
        entity.setCreatedAt(updatedAt);
        entity.setUpdatedAt(updatedAt);
        return entity;
    }
}
