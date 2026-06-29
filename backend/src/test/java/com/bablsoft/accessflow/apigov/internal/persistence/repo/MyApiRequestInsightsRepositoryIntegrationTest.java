package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the self-scoped API-request dashboard aggregations (AF-500) run against a real Postgres —
 * native {@code date_trunc} day-bucketing, status grouping, and the {@code ai_analyses} risk join.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class MyApiRequestInsightsRepositoryIntegrationTest {

    // Non-static so each test method (fresh instance) gets a unique scope — the shared @SpringBootTest
    // DB is not reset between methods, and every query filters by (organization_id, submitted_by).
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();

    @Autowired
    private MyApiRequestInsightsRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private ApiRequestEntity request(UUID submittedBy, QueryStatus status, Instant createdAt) {
        var e = new ApiRequestEntity();
        e.setId(UUID.randomUUID());
        e.setConnectorId(UUID.randomUUID());
        e.setOrganizationId(org);
        e.setSubmittedBy(submittedBy);
        e.setVerb("GET");
        e.setRequestPath("/v1/things");
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    void statusCountsGroupAndScopeToTheUser() {
        var now = Instant.now();
        repository.saveAll(List.of(
                request(user, QueryStatus.PENDING_REVIEW, now),
                request(user, QueryStatus.PENDING_REVIEW, now),
                request(user, QueryStatus.EXECUTED, now),
                request(otherUser, QueryStatus.PENDING_REVIEW, now)));

        var counts = repository.findStatusCounts(org, user);

        assertThat(counts).hasSize(2);
        assertThat(counts).filteredOn(c -> "PENDING_REVIEW".equals(c.getStatus()))
                .singleElement().satisfies(c -> assertThat(c.getCnt()).isEqualTo(2));
        assertThat(counts).filteredOn(c -> "EXECUTED".equals(c.getStatus()))
                .singleElement().satisfies(c -> assertThat(c.getCnt()).isEqualTo(1));
    }

    @Test
    void statusByDayBucketsByDayWithinWindow() {
        // Mid-day timestamps (well-separated days) so date_trunc's server-local-day bucketing is stable
        // regardless of the JDBC session timezone pgjdbc derives from the JVM default zone.
        var dayOne = Instant.parse("2026-03-01T10:00:00Z");
        var dayOneLater = Instant.parse("2026-03-01T14:00:00Z");
        var dayTwo = Instant.parse("2026-03-05T12:00:00Z");
        var outside = Instant.parse("2026-04-01T12:00:00Z");
        repository.saveAll(List.of(
                request(user, QueryStatus.EXECUTED, dayOne),
                request(user, QueryStatus.EXECUTED, dayOneLater),
                request(user, QueryStatus.EXECUTED, dayTwo),
                request(user, QueryStatus.EXECUTED, outside)));

        var rows = repository.findStatusByDay(org, user,
                Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-31T00:00:00Z"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getCnt()).isEqualTo(2);
        assertThat(rows.get(1).getCnt()).isEqualTo(1);
        assertThat(rows.get(0).getBucketDate()).isBefore(rows.get(1).getBucketDate());
    }

    @Test
    void riskByDayJoinsAiAnalysesAndExcludesFailed() {
        var created = Instant.parse("2026-05-10T09:00:00Z");
        var analyzed = request(user, QueryStatus.EXECUTED, created);
        var failedAnalyzed = request(user, QueryStatus.EXECUTED, created);
        repository.saveAll(List.of(analyzed, failedAnalyzed));

        var goodAnalysisId = insertAnalysis(analyzed.getId(), "HIGH", false);
        var failedAnalysisId = insertAnalysis(failedAnalyzed.getId(), "LOW", true);
        linkAnalysis(analyzed.getId(), goodAnalysisId);
        linkAnalysis(failedAnalyzed.getId(), failedAnalysisId);

        var rows = repository.findRiskByDay(org, user,
                Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-31T00:00:00Z"));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getRiskLevel()).isEqualTo("HIGH");
            assertThat(r.getCnt()).isEqualTo(1);
            assertThat(r.getBucketDate()).isEqualTo(java.time.LocalDate.of(2026, 5, 10));
        });
    }

    private UUID insertAnalysis(UUID apiRequestId, String riskLevel, boolean failed) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_analyses (id, api_request_id, ai_provider, ai_model, risk_score,
                                         risk_level, summary, failed, created_at)
                VALUES (?, ?, CAST(? AS ai_provider), ?, ?, CAST(? AS risk_level), ?, ?, ?)
                """,
                id, apiRequestId, "ANTHROPIC", "claude", 50, riskLevel, "summary", failed,
                Timestamp.from(Instant.now()));
        return id;
    }

    private void linkAnalysis(UUID apiRequestId, UUID analysisId) {
        jdbcTemplate.update("UPDATE api_requests SET ai_analysis_id = ? WHERE id = ?",
                analysisId, apiRequestId);
    }
}
