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
    void throttledDueScanFindsApprovedRequestsPastTheirThrottledUntilAndScheduledScanSkipsThem() {
        var now = Instant.now();
        var due = newRequest(QueryStatus.APPROVED);
        due.setThrottledUntil(now.minus(1, ChronoUnit.MINUTES));
        var later = newRequest(QueryStatus.APPROVED);
        later.setThrottledUntil(now.plus(1, ChronoUnit.MINUTES));
        var scheduledAndThrottled = newRequest(QueryStatus.APPROVED);
        scheduledAndThrottled.setScheduledFor(now.minus(1, ChronoUnit.HOURS));
        scheduledAndThrottled.setThrottledUntil(now.minus(1, ChronoUnit.MINUTES));
        var cancelled = newRequest(QueryStatus.CANCELLED);
        cancelled.setThrottledUntil(now.minus(1, ChronoUnit.MINUTES));
        repository.saveAll(java.util.List.of(due, later, scheduledAndThrottled, cancelled));

        assertThat(repository.findThrottledDueIds(now))
                .contains(due.getId(), scheduledAndThrottled.getId())
                .doesNotContain(later.getId(), cancelled.getId());
        assertThat(repository.findScheduledDueIds(now)).doesNotContain(scheduledAndThrottled.getId());
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

    private ApiRequestEntity run(UUID orgId, UUID connectorId, UUID submittedBy, String operationId,
                                 QueryStatus status, String snapshot, Instant createdAt) {
        var e = newRequest(status);
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setSubmittedBy(submittedBy);
        e.setOperationId(operationId);
        e.setResponseSnapshot(snapshot);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    void previousRunByOperationPicksTheNewestEarlierExecutedRunWithASnapshot() {
        var org = UUID.randomUUID();
        var connector = UUID.randomUUID();
        var user = UUID.randomUUID();
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var current = run(org, connector, user, "listOrders", QueryStatus.EXECUTED, "{}", now);
        var older = run(org, connector, user, "listOrders", QueryStatus.EXECUTED, "{}", now.minusSeconds(300));
        var newest = run(org, connector, user, "listOrders", QueryStatus.EXECUTED, "{}", now.minusSeconds(60));
        var noSnapshot = run(org, connector, user, "listOrders", QueryStatus.EXECUTED, null, now.minusSeconds(10));
        var failed = run(org, connector, user, "listOrders", QueryStatus.FAILED, "{}", now.minusSeconds(20));
        var later = run(org, connector, user, "listOrders", QueryStatus.EXECUTED, "{}", now.plusSeconds(60));
        var otherOperation = run(org, connector, user, "getOrder", QueryStatus.EXECUTED, "{}", now.minusSeconds(5));
        var otherOrg = run(UUID.randomUUID(), connector, user, "listOrders", QueryStatus.EXECUTED, "{}",
                now.minusSeconds(5));
        repository.saveAll(java.util.List.of(current, older, newest, noSnapshot, failed, later, otherOperation,
                otherOrg));

        var previous = repository.findPreviousRunByOperation(org, connector, "listOrders", now, current.getId(),
                null);

        assertThat(previous).map(ApiRequestEntity::getId).contains(newest.getId());
    }

    @Test
    void previousRunIsScopedToTheSubmitterWhenGiven() {
        var org = UUID.randomUUID();
        var connector = UUID.randomUUID();
        var user = UUID.randomUUID();
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var current = run(org, connector, user, "listOrders", QueryStatus.EXECUTED, "{}", now);
        var mine = run(org, connector, user, "listOrders", QueryStatus.EXECUTED, "{}", now.minusSeconds(300));
        var someoneElses = run(org, connector, UUID.randomUUID(), "listOrders", QueryStatus.EXECUTED, "{}",
                now.minusSeconds(60));
        repository.saveAll(java.util.List.of(current, mine, someoneElses));

        assertThat(repository.findPreviousRunByOperation(org, connector, "listOrders", now, current.getId(), user))
                .map(ApiRequestEntity::getId).contains(mine.getId());
        assertThat(repository.findPreviousRunByOperation(org, connector, "listOrders", now, current.getId(), null))
                .map(ApiRequestEntity::getId).contains(someoneElses.getId());
    }

    @Test
    void previousRunByVerbAndPathOnlyMatchesFreeFormCalls() {
        var org = UUID.randomUUID();
        var connector = UUID.randomUUID();
        var user = UUID.randomUUID();
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var current = run(org, connector, user, null, QueryStatus.EXECUTED, "{}", now);
        var freeForm = run(org, connector, user, null, QueryStatus.EXECUTED, "{}", now.minusSeconds(300));
        var withOperation = run(org, connector, user, "listThings", QueryStatus.EXECUTED, "{}",
                now.minusSeconds(60));
        var otherPath = run(org, connector, user, null, QueryStatus.EXECUTED, "{}", now.minusSeconds(30));
        otherPath.setRequestPath("/v1/other");
        var otherVerb = run(org, connector, user, null, QueryStatus.EXECUTED, "{}", now.minusSeconds(20));
        otherVerb.setVerb("POST");
        repository.saveAll(java.util.List.of(current, freeForm, withOperation, otherPath, otherVerb));

        var previous = repository.findPreviousRunByVerbAndPath(org, connector, "GET", "/v1/things", now,
                current.getId(), null);

        assertThat(previous).map(ApiRequestEntity::getId).contains(freeForm.getId());
        assertThat(repository.findPreviousRunByVerbAndPath(org, connector, "GET", "/v1/things",
                now.minusSeconds(300), freeForm.getId(), null)).isEmpty();
    }
}
