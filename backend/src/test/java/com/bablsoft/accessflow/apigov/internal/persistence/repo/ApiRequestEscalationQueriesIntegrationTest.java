package com.bablsoft.accessflow.apigov.internal.persistence.repo;

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

/** The #622 escalation and nudge scans on {@code api_requests}, against real PostgreSQL. */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiRequestEscalationQueriesIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Autowired ApiRequestRepository repository;
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
    void escalationScanReturnsOnlyRequestsPastTheirConnectorPlanWindow() {
        var connector = connectorWithPlan(4, null);
        var due = pending(connector, NOW.minusSeconds(5 * 3600), null);
        pending(connector, NOW.minusSeconds(3600), null);

        assertThat(repository.findEscalationDueIds(NOW)).containsExactly(due);
    }

    @Test
    void escalationScanSkipsConnectorsWithoutAReviewPlan() {
        // review_plan_id is nullable on api_connectors, and the scan inner-joins the plan.
        var connector = EscalationFixtures.apiConnector(jdbc, organizationId, null);
        pending(connector, NOW.minusSeconds(500 * 3600), null);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
        assertThat(repository.findNudgeDueIds(NOW)).isEmpty();
    }

    @Test
    void escalationScanFiresOncePerRequest() {
        var connector = connectorWithPlan(1, null);
        var due = pending(connector, NOW.minusSeconds(5 * 3600), null);
        jdbc.update("UPDATE api_requests SET escalated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), due);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
    }

    @Test
    void nudgeScanMeasuresFromTheLastReminderWhenThereIsOne() {
        var connector = connectorWithPlan(null, 2);
        pending(connector, NOW.minusSeconds(90 * 3600), NOW.minusSeconds(60));
        var due = pending(connector, NOW.minusSeconds(90 * 3600), NOW.minusSeconds(3 * 3600));

        assertThat(repository.findNudgeDueIds(NOW)).containsExactly(due);
    }

    @Test
    void neitherScanSeesRequestsThatAreNoLongerPendingReview() {
        var connector = connectorWithPlan(1, 1);
        var request = pending(connector, NOW.minusSeconds(90 * 3600), null);
        jdbc.update("UPDATE api_requests SET status = 'APPROVED'::query_status WHERE id = ?",
                request);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
        assertThat(repository.findNudgeDueIds(NOW)).isEmpty();
    }

    private UUID connectorWithPlan(Integer escalationAfterHours, Integer nudgeIntervalHours) {
        var plan = EscalationFixtures.reviewPlan(jdbc, organizationId, escalationAfterHours,
                nudgeIntervalHours);
        return EscalationFixtures.apiConnector(jdbc, organizationId, plan);
    }

    private UUID pending(UUID connectorId, Instant createdAt, Instant lastNudgedAt) {
        return EscalationFixtures.pendingApiRequest(jdbc, organizationId, connectorId, submitter,
                createdAt, lastNudgedAt);
    }
}
