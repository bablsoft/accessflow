package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryDetailResponseTest {

    @Test
    void fromCopiesAllFieldsAndNestsRefs() {
        var queryId = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var submitterId = UUID.randomUUID();
        var aiId = UUID.randomUUID();
        var ai = new QueryDetailView.AiAnalysisDetail(
                aiId, RiskLevel.MEDIUM, 42, "summary",
                "[{\"severity\":\"HIGH\"}]", "[{\"type\":\"INDEX\"}]", true, 17L,
                AiProviderType.ANTHROPIC, "claude-sonnet-4", 100, 50,
                false, null);
        var reviewerId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        var decision = new QueryDetailView.ReviewDecisionView(
                decisionId,
                new QueryDetailView.ReviewerRef(reviewerId, "bob@example.com", "Bob"),
                DecisionType.APPROVED,
                "lgtm",
                1,
                Instant.parse("2026-05-01T10:00:20Z"));
        var scheduledFor = Instant.parse("2026-05-02T08:00:00Z");
        var priorRunId = UUID.randomUUID();
        var view = new QueryDetailView(queryId, dsId, "Prod PG", DbType.POSTGRESQL, orgId,
                submitterId, "alice@example.com", "Alice",
                "SELECT 1", QueryType.SELECT, QueryStatus.EXECUTED,
                "ticket-42", ai, 5L, 99, null,
                priorRunId,
                "Prod plan", 24,
                List.of(decision),
                scheduledFor,
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-01T10:00:30Z"));

        var response = QueryDetailResponse.from(view);

        assertThat(response.id()).isEqualTo(queryId);
        assertThat(response.reviewDecisions()).hasSize(1);
        var decisionOut = response.reviewDecisions().get(0);
        assertThat(decisionOut.id()).isEqualTo(decisionId);
        assertThat(decisionOut.reviewer())
                .isEqualTo(new QueryDetailResponse.ReviewerRef(reviewerId, "bob@example.com", "Bob"));
        assertThat(decisionOut.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(decisionOut.comment()).isEqualTo("lgtm");
        assertThat(decisionOut.stage()).isEqualTo(1);
        assertThat(decisionOut.decidedAt()).isEqualTo(Instant.parse("2026-05-01T10:00:20Z"));
        assertThat(response.datasource()).isEqualTo(new QueryListItem.DatasourceRef(dsId, "Prod PG"));
        assertThat(response.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(response.submittedBy())
                .isEqualTo(new QueryListItem.SubmitterRef(submitterId, "alice@example.com", "Alice"));
        assertThat(response.sqlText()).isEqualTo("SELECT 1");
        assertThat(response.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(response.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(response.justification()).isEqualTo("ticket-42");
        assertThat(response.rowsAffected()).isEqualTo(5L);
        assertThat(response.durationMs()).isEqualTo(99);
        assertThat(response.errorMessage()).isNull();
        assertThat(response.previousRunId()).isEqualTo(priorRunId);
        assertThat(response.reviewPlanName()).isEqualTo("Prod plan");
        assertThat(response.approvalTimeoutHours()).isEqualTo(24);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-05-01T10:00:30Z"));
        assertThat(response.scheduledFor()).isEqualTo(scheduledFor);

        var aiOut = response.aiAnalysis();
        assertThat(aiOut).isNotNull();
        assertThat(aiOut.id()).isEqualTo(aiId);
        assertThat(aiOut.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(aiOut.riskScore()).isEqualTo(42);
        assertThat(aiOut.summary()).isEqualTo("summary");
        assertThat(aiOut.issues()).isEqualTo("[{\"severity\":\"HIGH\"}]");
        assertThat(aiOut.optimizations()).isEqualTo("[{\"type\":\"INDEX\"}]");
        assertThat(aiOut.missingIndexesDetected()).isTrue();
        assertThat(aiOut.affectsRowEstimate()).isEqualTo(17L);
        assertThat(aiOut.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(aiOut.aiModel()).isEqualTo("claude-sonnet-4");
        assertThat(aiOut.promptTokens()).isEqualTo(100);
        assertThat(aiOut.completionTokens()).isEqualTo(50);
        assertThat(aiOut.failed()).isFalse();
        assertThat(aiOut.errorMessage()).isNull();
    }

    @Test
    void aiAnalysisDetailCarriesFailureFlagAndReason() {
        var ai = new QueryDetailView.AiAnalysisDetail(
                UUID.randomUUID(), RiskLevel.CRITICAL, 100,
                "AI analysis failed: provider unavailable",
                "[]", "[]", false, null,
                AiProviderType.ANTHROPIC, "unknown", 0, 0,
                true, "provider unavailable");
        var view = new QueryDetailView(UUID.randomUUID(), UUID.randomUUID(), "ds", DbType.POSTGRESQL,
                UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "A",
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_REVIEW,
                "x", ai, null, null, null, null,
                null, null,
                List.of(),
                null,
                Instant.now(), Instant.now());

        var response = QueryDetailResponse.from(view);

        assertThat(response.aiAnalysis().failed()).isTrue();
        assertThat(response.aiAnalysis().errorMessage()).isEqualTo("provider unavailable");
    }

    @Test
    void aiAnalysisDetailIsNullWhenViewHasNoAnalysis() {
        var view = new QueryDetailView(UUID.randomUUID(), UUID.randomUUID(), "ds", DbType.POSTGRESQL,
                UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "A",
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI,
                "x", null, null, null, null, null,
                null, null,
                List.of(),
                null,
                Instant.now(), Instant.now());

        var response = QueryDetailResponse.from(view);

        assertThat(response.aiAnalysis()).isNull();
        assertThat(response.reviewPlanName()).isNull();
        assertThat(response.approvalTimeoutHours()).isNull();
        assertThat(response.reviewDecisions()).isEmpty();
        assertThat(response.scheduledFor()).isNull();
        assertThat(response.matchedPolicy()).isNull();
    }

    @Test
    void matchedPolicyIsNullWithoutRoutingDecision() {
        var response = QueryDetailResponse.from(minimalView());

        assertThat(response.matchedPolicy()).isNull();
    }

    @Test
    void matchedPolicyIsPopulatedFromRoutingDecision() {
        var policyId = UUID.randomUUID();
        var matched = new com.bablsoft.accessflow.workflow.internal.routing.MatchedRoutingPolicyView(
                policyId, "Block payroll deletes",
                com.bablsoft.accessflow.workflow.api.RoutingAction.AUTO_REJECT, "payroll protected");

        var response = QueryDetailResponse.from(minimalView(), matched);

        assertThat(response.matchedPolicy()).isNotNull();
        assertThat(response.matchedPolicy().policyId()).isEqualTo(policyId);
        assertThat(response.matchedPolicy().policyName()).isEqualTo("Block payroll deletes");
        assertThat(response.matchedPolicy().action())
                .isEqualTo(com.bablsoft.accessflow.workflow.api.RoutingAction.AUTO_REJECT);
        assertThat(response.matchedPolicy().reason()).isEqualTo("payroll protected");
    }

    private QueryDetailView minimalView() {
        return new QueryDetailView(UUID.randomUUID(), UUID.randomUUID(), "ds", DbType.POSTGRESQL,
                UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "A",
                "SELECT 1", QueryType.SELECT, QueryStatus.REJECTED,
                "x", null, null, null, null, null,
                null, null,
                List.of(),
                null,
                Instant.now(), Instant.now());
    }

    @Test
    void aiAnalysisDetailFallsBackToEmptyArrayWhenIssuesJsonIsNull() {
        var ai = new QueryDetailView.AiAnalysisDetail(
                UUID.randomUUID(), RiskLevel.LOW, 5, "ok",
                null, null, false, null,
                AiProviderType.OPENAI, "gpt-4o", 0, 0,
                false, null);
        var view = new QueryDetailView(UUID.randomUUID(), UUID.randomUUID(), "ds", DbType.POSTGRESQL,
                UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "A",
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_REVIEW,
                "x", ai, null, null, null, null,
                null, null,
                List.of(),
                null,
                Instant.now(), Instant.now());

        var response = QueryDetailResponse.from(view);

        assertThat(response.aiAnalysis().issues()).isEqualTo("[]");
        assertThat(response.aiAnalysis().optimizations()).isEqualTo("[]");
    }
}
