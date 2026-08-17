package com.bablsoft.accessflow.requestgroups.internal.persistence.repo;

import com.bablsoft.accessflow.EscalationFixtures;
import com.bablsoft.accessflow.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The #622 escalation and nudge scans on {@code request_groups}, against real PostgreSQL.
 *
 * <p>This is the most intricate of the three: a bundle has no plan of its own, so the window is the
 * <strong>minimum</strong> non-null value across its members' plans, reached through two optional
 * joins ({@code datasources} or {@code api_connectors}) and a correlated subquery. The
 * {@code EXISTS} guard beside the {@code COALESCE(MIN(...), 0)} is what stops a bundle whose
 * members all have escalation switched off from reading as instantly due — that is asserted here
 * directly, because it is invisible from the Java side.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class RequestGroupEscalationQueriesIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Autowired RequestGroupRepository repository;
    @Autowired JdbcTemplate jdbc;

    private UUID organizationId;
    private UUID submitter;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

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
    void escalationWindowIsTheMinimumAcrossMemberPlans() {
        var group = pendingGroup(NOW.minusSeconds(5 * 3600), null);
        EscalationFixtures.queryMember(jdbc, group, 1, datasourceWithPlan(4, null));
        EscalationFixtures.queryMember(jdbc, group, 2, datasourceWithPlan(48, null));

        // 5h elapsed: past the strictest member's 4h, nowhere near the other's 48h.
        assertThat(repository.findEscalationDueIds(NOW)).containsExactly(group);
    }

    @Test
    void escalationWindowSpansDatasourceAndConnectorMembers() {
        var group = pendingGroup(NOW.minusSeconds(5 * 3600), null);
        EscalationFixtures.queryMember(jdbc, group, 1, datasourceWithPlan(48, null));
        EscalationFixtures.apiMember(jdbc, group, 2, connectorWithPlan(4, null));

        assertThat(repository.findEscalationDueIds(NOW)).containsExactly(group);
    }

    @Test
    void aBundleWhoseMembersAllHaveEscalationOffIsNeverDue() {
        var group = pendingGroup(NOW.minusSeconds(5000 * 3600), null);
        EscalationFixtures.queryMember(jdbc, group, 1, datasourceWithPlan(null, null));

        // Without the EXISTS guard the COALESCE(..., 0) would make this instantly due.
        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
    }

    @Test
    void aBundleWithNoMembersIsNeverDue() {
        pendingGroup(NOW.minusSeconds(5000 * 3600), null);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
        assertThat(repository.findNudgeDueIds(NOW)).isEmpty();
    }

    @Test
    void aBundleInsideItsWindowIsNotDue() {
        var group = pendingGroup(NOW.minusSeconds(3600), null);
        EscalationFixtures.queryMember(jdbc, group, 1, datasourceWithPlan(4, null));

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
    }

    @Test
    void escalationFiresOncePerBundle() {
        var group = pendingGroup(NOW.minusSeconds(5 * 3600), null);
        EscalationFixtures.queryMember(jdbc, group, 1, datasourceWithPlan(1, null));
        jdbc.update("UPDATE request_groups SET escalated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), group);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
    }

    @Test
    void nudgeCadenceIsTheMinimumAcrossMemberPlansAndRunsFromTheLastReminder() {
        var reminded = pendingGroup(NOW.minusSeconds(900 * 3600), NOW.minusSeconds(60));
        EscalationFixtures.queryMember(jdbc, reminded, 1, datasourceWithPlan(null, 2));

        var due = pendingGroup(NOW.minusSeconds(900 * 3600), NOW.minusSeconds(3 * 3600));
        EscalationFixtures.queryMember(jdbc, due, 1, datasourceWithPlan(null, 24));
        EscalationFixtures.queryMember(jdbc, due, 2, datasourceWithPlan(null, 2));

        assertThat(repository.findNudgeDueIds(NOW)).containsExactly(due);
    }

    @Test
    void aBundleWhoseMembersAllHaveNudgesOffIsNeverDue() {
        var group = pendingGroup(NOW.minusSeconds(5000 * 3600), null);
        EscalationFixtures.queryMember(jdbc, group, 1, datasourceWithPlan(4, null));

        assertThat(repository.findNudgeDueIds(NOW)).isEmpty();
    }

    @Test
    void neitherScanSeesBundlesThatAreNoLongerPendingReview() {
        var group = pendingGroup(NOW.minusSeconds(900 * 3600), null);
        EscalationFixtures.queryMember(jdbc, group, 1, datasourceWithPlan(1, 1));
        jdbc.update("UPDATE request_groups SET status = 'APPROVED'::request_group_status "
                + "WHERE id = ?", group);

        assertThat(repository.findEscalationDueIds(NOW)).isEmpty();
        assertThat(repository.findNudgeDueIds(NOW)).isEmpty();
    }

    private UUID pendingGroup(Instant createdAt, Instant lastNudgedAt) {
        return EscalationFixtures.pendingGroup(jdbc, organizationId, submitter, createdAt,
                lastNudgedAt);
    }

    private UUID datasourceWithPlan(Integer escalationAfterHours, Integer nudgeIntervalHours) {
        var plan = EscalationFixtures.reviewPlan(jdbc, organizationId, escalationAfterHours,
                nudgeIntervalHours);
        return EscalationFixtures.datasource(jdbc, organizationId, plan);
    }

    private UUID connectorWithPlan(Integer escalationAfterHours, Integer nudgeIntervalHours) {
        var plan = EscalationFixtures.reviewPlan(jdbc, organizationId, escalationAfterHours,
                nudgeIntervalHours);
        return EscalationFixtures.apiConnector(jdbc, organizationId, plan);
    }
}
