package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.EscalationFixtures;
import com.bablsoft.accessflow.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The #622 escalation and nudge scans on {@code query_requests}, against real PostgreSQL.
 *
 * <p>These are native queries with hand-written joins and enum casts, so nothing in the unit tests
 * — which mock the repository — can tell a mistyped column or a broken interval expression from a
 * working one. The job swallows per-row {@code RuntimeException}s, so such a mistake would surface
 * as a job that quietly never escalates anything.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryRequestEscalationQueriesIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Autowired QueryRequestRepository repository;
    @Autowired JdbcTemplate jdbc;

    private UUID organizationId;
    private UUID submitter;

    @BeforeEach
    void setUp() {
        EscalationFixtures.cleanup(jdbc);
        organizationId = EscalationFixtures.organization(jdbc);
        submitter = EscalationFixtures.user(jdbc, organizationId);
    }

    @AfterEach
    void tearDown() {
        EscalationFixtures.cleanup(jdbc);
    }

    @Test
    void escalationScanReturnsOnlyRequestsPastTheirPlanWindow() {
        var datasource = datasourceWithPlan(4, null);
        var due = pending(datasource, NOW.minusSeconds(5 * 3600), null);
        pending(datasource, NOW.minusSeconds(3600), null);

        assertThat(repository.findEscalationDueIds(NOW)).containsExactly(due);
    }

    @Test
    void escalationScanSkipsPlansWithEscalationDisabled() {
        var datasource = datasourceWithPlan(null, null);
        pending(datasource, NOW.minusSeconds(500 * 3600), null);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
    }

    @Test
    void escalationScanFiresOncePerRequest() {
        var datasource = datasourceWithPlan(1, null);
        var due = pending(datasource, NOW.minusSeconds(5 * 3600), null);

        jdbc.update("UPDATE query_requests SET escalated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), due);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
    }

    @Test
    void nudgeScanUsesSubmissionTimeUntilTheFirstReminderGoesOut() {
        var datasource = datasourceWithPlan(null, 2);
        var due = pending(datasource, NOW.minusSeconds(3 * 3600), null);
        pending(datasource, NOW.minusSeconds(3600), null);

        assertThat(repository.findNudgeDueIds(NOW)).containsExactly(due);
    }

    @Test
    void nudgeScanMeasuresFromTheLastReminderOnceOneHasGoneOut() {
        var datasource = datasourceWithPlan(null, 2);
        // Submitted long ago, but reminded a minute ago: not due again yet.
        pending(datasource, NOW.minusSeconds(90 * 3600), NOW.minusSeconds(60));
        var due = pending(datasource, NOW.minusSeconds(90 * 3600), NOW.minusSeconds(3 * 3600));

        assertThat(repository.findNudgeDueIds(NOW)).containsExactly(due);
    }

    @Test
    void neitherScanSeesRequestsThatAreNoLongerPendingReview() {
        var datasource = datasourceWithPlan(1, 1);
        var request = pending(datasource, NOW.minusSeconds(90 * 3600), null);
        jdbc.update("UPDATE query_requests SET status = 'APPROVED'::query_status WHERE id = ?",
                request);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
        assertThat(repository.findNudgeDueIds(NOW)).isEmpty();
    }

    private UUID datasourceWithPlan(Integer escalationAfterHours, Integer nudgeIntervalHours) {
        var plan = EscalationFixtures.reviewPlan(jdbc, organizationId, escalationAfterHours,
                nudgeIntervalHours);
        return EscalationFixtures.datasource(jdbc, organizationId, plan);
    }

    private UUID pending(UUID datasourceId, Instant createdAt, Instant lastNudgedAt) {
        return EscalationFixtures.pendingQuery(jdbc, datasourceId, submitter, createdAt,
                lastNudgedAt);
    }
}
