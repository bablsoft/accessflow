package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the apigov scheduled/timeout scans run against a real Postgres — these use native queries
 * with an explicit {@code ::query_status} cast (a JPQL enum literal made Hibernate cast to a
 * non-existent "querystatus" type, which broke the scheduled jobs at runtime).
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiRequestRepositoryIntegrationTest {

    @Autowired
    private ApiRequestRepository repository;

    private ApiRequestEntity newRequest(QueryStatus status) {
        var e = new ApiRequestEntity();
        e.setId(UUID.randomUUID());
        e.setConnectorId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setSubmittedBy(UUID.randomUUID());
        e.setVerb("GET");
        e.setRequestPath("/v1/things");
        e.setStatus(status);
        return e;
    }

    @Test
    void scheduledDueScanFindsApprovedRequestsPastTheirScheduledFor() {
        var now = Instant.now();
        var due = newRequest(QueryStatus.APPROVED);
        due.setScheduledFor(now.minus(1, ChronoUnit.HOURS));
        var future = newRequest(QueryStatus.APPROVED);
        future.setScheduledFor(now.plus(1, ChronoUnit.HOURS));
        var unscheduled = newRequest(QueryStatus.APPROVED);
        repository.saveAll(java.util.List.of(due, future, unscheduled));

        var dueIds = repository.findScheduledDueIds(now);

        assertThat(dueIds).contains(due.getId()).doesNotContain(future.getId(), unscheduled.getId());
    }

    @Test
    void stalePendingReviewScanFindsRequestsOlderThanCutoff() {
        var stale = newRequest(QueryStatus.PENDING_REVIEW);
        stale.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        var fresh = newRequest(QueryStatus.PENDING_REVIEW);
        fresh.setCreatedAt(Instant.now());
        repository.saveAll(java.util.List.of(stale, fresh));

        var staleIds = repository.findStalePendingReviewIds(Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(staleIds).contains(stale.getId()).doesNotContain(fresh.getId());
    }
}
