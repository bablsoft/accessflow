package com.bablsoft.accessflow.access;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.api.GrantUsageService;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The over-provisioned report against real PostgreSQL (#625).
 *
 * <p>This exists because the controller test {@code @MockitoBean}s the service, so it can never
 * exercise the actual query — and the first version of that query did not run at all. It filtered
 * with the usual {@code (:param is null or col = :param)} idiom, which Postgres rejects outright on
 * this table ("could not determine data type of parameter"): {@code resource_kind} and
 * {@code recommendation} are PG enum columns, and a bound parameter appearing only in an
 * {@code IS NULL} test gives the planner nothing to infer a type from. Every filter below is
 * therefore a regression guard, not just a behaviour check.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class GrantUsageReportIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired GrantUsageService grantUsageService;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID datasourceId;
    private UUID connectorId;
    private UUID staleUserId;
    private UUID activeUserId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM grant_usage_summary");
        organizationId = UUID.randomUUID();
        datasourceId = UUID.randomUUID();
        connectorId = UUID.randomUUID();
        staleUserId = UUID.randomUUID();
        activeUserId = UUID.randomUUID();

        // Never used: no last_used_at at all — the row that must sort first.
        insert(GrantResourceKind.DATASOURCE, datasourceId, "analytics", staleUserId,
                "stale@example.test", 12, 0, 0, null, GrantUsageRecommendation.NEVER_USED);
        // Idle but previously used.
        insert(GrantResourceKind.DATASOURCE, datasourceId, "analytics", UUID.randomUUID(),
                "idle@example.test", 4, 1, 9, NOW.minus(Duration.ofDays(90)),
                GrantUsageRecommendation.STALE);
        // Active connector grant — exercises the other resource kind.
        insert(GrantResourceKind.API_CONNECTOR, connectorId, "billing", activeUserId,
                "active@example.test", null, 3, 40, NOW.minus(Duration.ofDays(1)),
                GrantUsageRecommendation.ACTIVE);
    }

    private void insert(GrantResourceKind kind, UUID resourceId, String resourceName, UUID userId,
                        String email, Integer grantedTargets, int usedTargets, long usageCount,
                        Instant lastUsedAt, GrantUsageRecommendation recommendation) {
        jdbcTemplate.update("""
                INSERT INTO grant_usage_summary (
                    id, organization_id, resource_kind, resource_id, resource_name, permission_id,
                    user_id, user_email, granted_at, granted_target_count, used_targets,
                    used_target_count, usage_count, last_used_at, observed_since, recommendation)
                VALUES (?, ?, ?::grant_resource_kind, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?,
                        ?::grant_usage_recommendation)
                """,
                UUID.randomUUID(), organizationId, kind.name(), resourceId, resourceName,
                UUID.randomUUID(), userId, email, java.sql.Timestamp.from(NOW.minus(Duration.ofDays(200))),
                grantedTargets, "[]", usedTargets, usageCount,
                lastUsedAt == null ? null : java.sql.Timestamp.from(lastUsedAt),
                java.sql.Timestamp.from(NOW.minus(Duration.ofDays(90))), recommendation.name());
    }

    private java.util.List<String> emails(GrantUsageReportQuery query) {
        return grantUsageService.report(organizationId, query, PageRequest.of(0, 20))
                .content().stream().map(v -> v.userEmail()).toList();
    }

    @Test
    void anUnfilteredReportReturnsEveryGrantWorstFirst() {
        var emails = emails(GrantUsageReportQuery.empty());

        assertThat(emails).hasSize(3);
        // Never-used (null last_used_at) sorts ahead of merely idle, which sorts ahead of active.
        assertThat(emails.get(0)).isEqualTo("stale@example.test");
        assertThat(emails.get(1)).isEqualTo("idle@example.test");
        assertThat(emails.get(2)).isEqualTo("active@example.test");
    }

    @Test
    void filtersByResourceKind() {
        assertThat(emails(new GrantUsageReportQuery(GrantResourceKind.API_CONNECTOR, Set.of(),
                null, null))).containsExactly("active@example.test");
        assertThat(emails(new GrantUsageReportQuery(GrantResourceKind.DATASOURCE, Set.of(),
                null, null)))
                .containsExactlyInAnyOrder("stale@example.test", "idle@example.test");
    }

    @Test
    void filtersByASingleRecommendation() {
        assertThat(emails(new GrantUsageReportQuery(null,
                Set.of(GrantUsageRecommendation.NEVER_USED), null, null)))
                .containsExactly("stale@example.test");
    }

    @Test
    void filtersBySeveralRecommendationsAtOnce() {
        assertThat(emails(new GrantUsageReportQuery(null,
                Set.of(GrantUsageRecommendation.NEVER_USED, GrantUsageRecommendation.STALE),
                null, null)))
                .containsExactlyInAnyOrder("stale@example.test", "idle@example.test");
    }

    @Test
    void filtersByResourceAndByUser() {
        assertThat(emails(new GrantUsageReportQuery(null, Set.of(), connectorId, null)))
                .containsExactly("active@example.test");
        assertThat(emails(new GrantUsageReportQuery(null, Set.of(), null, staleUserId)))
                .containsExactly("stale@example.test");
    }

    @Test
    void combinesEveryFilter() {
        assertThat(emails(new GrantUsageReportQuery(GrantResourceKind.API_CONNECTOR,
                Set.of(GrantUsageRecommendation.ACTIVE), connectorId, activeUserId)))
                .containsExactly("active@example.test");
        // A combination no row satisfies must return empty rather than ignoring a filter.
        assertThat(emails(new GrantUsageReportQuery(GrantResourceKind.DATASOURCE,
                Set.of(GrantUsageRecommendation.ACTIVE), null, null))).isEmpty();
    }

    @Test
    void honoursAnExplicitSortOverTheWorstFirstDefault() {
        var page = grantUsageService.report(organizationId, GrantUsageReportQuery.empty(),
                PageRequest.of(0, 20, SortOrder.desc("usageCount")));

        assertThat(page.content()).extracting(v -> v.userEmail())
                .containsExactly("active@example.test", "idle@example.test", "stale@example.test");
    }

    @Test
    void pagesWithATotalCount() {
        var first = grantUsageService.report(organizationId, GrantUsageReportQuery.empty(),
                PageRequest.of(0, 2));

        assertThat(first.content()).hasSize(2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    void findsASingleGrantAndDistinguishesUnrestrictedFromZeroScope() {
        var unrestricted = grantUsageService.findFor(organizationId,
                GrantResourceKind.API_CONNECTOR, connectorId, activeUserId).orElseThrow();
        assertThat(unrestricted.grantedTargetCount()).isNull();
        assertThat(unrestricted.unusedTargetCount()).isNull();

        var scoped = grantUsageService.findFor(organizationId, GrantResourceKind.DATASOURCE,
                datasourceId, staleUserId).orElseThrow();
        assertThat(scoped.grantedTargetCount()).isEqualTo(12);
        assertThat(scoped.unusedTargetCount()).isEqualTo(12);
        assertThat(scoped.daysSinceLastUse(NOW)).isNull();
    }

    /** Two decimals, not seventeen — the CSV is read by people, and the input is whole days. */
    @Test
    void usagePerWeekIsRoundedRatherThanRawFloatingPoint() {
        var active = grantUsageService.findFor(organizationId, GrantResourceKind.API_CONNECTOR,
                connectorId, activeUserId).orElseThrow();

        // 40 uses over a 90-day observation window.
        assertThat(active.usagePerWeek(NOW)).isEqualTo(3.11);
    }

    @Test
    void isScopedToTheCallersOrganization() {
        assertThat(emails(GrantUsageReportQuery.empty())).hasSize(3);
        assertThat(grantUsageService.report(UUID.randomUUID(), GrantUsageReportQuery.empty(),
                PageRequest.of(0, 20)).content()).isEmpty();
    }
}
