package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentRequestEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new DeploymentRequestEntity();
        var id = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var submittedBy = UUID.randomUUID();
        var aiAnalysisId = UUID.randomUUID();
        var scheduledFor = Instant.parse("2026-08-03T08:00:00Z");
        var outcomeReportedAt = Instant.parse("2026-08-03T09:00:00Z");
        var createdAt = Instant.parse("2026-08-01T10:00:00Z");
        var updatedAt = Instant.parse("2026-08-02T10:00:00Z");

        entity.setId(id);
        entity.setPipelineId(pipelineId);
        entity.setEnvironmentId(environmentId);
        entity.setOrganizationId(organizationId);
        entity.setSubmittedBy(submittedBy);
        entity.setVersion("2.4.1");
        entity.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        entity.setArtifactRef("ghcr.io/acme/payments-api:2.4.1");
        entity.setRunUrl("https://github.com/acme/payments-api/actions/runs/42");
        entity.setExternalRunId("42");
        entity.setMetadata("{\"changelog\":\"fix rounding\"}");
        entity.setStatus(QueryStatus.PENDING_REVIEW);
        entity.setSubmissionReason(SubmissionReason.EMERGENCY_ACCESS);
        entity.setJustification("hotfix");
        entity.setAiAnalysisId(aiAnalysisId);
        entity.setRequiredApprovals(2);
        entity.setScheduledFor(scheduledFor);
        entity.setOutcome(DeploymentOutcome.ROLLED_BACK);
        entity.setOutcomeReportedAt(outcomeReportedAt);
        entity.setOutcomeDetail("smoke tests failed");
        entity.setSubmittedIp("203.0.113.7");
        entity.setVersionLock(3L);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getPipelineId()).isEqualTo(pipelineId);
        assertThat(entity.getEnvironmentId()).isEqualTo(environmentId);
        assertThat(entity.getOrganizationId()).isEqualTo(organizationId);
        assertThat(entity.getSubmittedBy()).isEqualTo(submittedBy);
        assertThat(entity.getVersion()).isEqualTo("2.4.1");
        assertThat(entity.getCommitSha()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(entity.getArtifactRef()).isEqualTo("ghcr.io/acme/payments-api:2.4.1");
        assertThat(entity.getRunUrl()).isEqualTo("https://github.com/acme/payments-api/actions/runs/42");
        assertThat(entity.getExternalRunId()).isEqualTo("42");
        assertThat(entity.getMetadata()).isEqualTo("{\"changelog\":\"fix rounding\"}");
        assertThat(entity.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(entity.getSubmissionReason()).isEqualTo(SubmissionReason.EMERGENCY_ACCESS);
        assertThat(entity.getJustification()).isEqualTo("hotfix");
        assertThat(entity.getAiAnalysisId()).isEqualTo(aiAnalysisId);
        assertThat(entity.getRequiredApprovals()).isEqualTo(2);
        assertThat(entity.getScheduledFor()).isEqualTo(scheduledFor);
        assertThat(entity.getOutcome()).isEqualTo(DeploymentOutcome.ROLLED_BACK);
        assertThat(entity.getOutcomeReportedAt()).isEqualTo(outcomeReportedAt);
        assertThat(entity.getOutcomeDetail()).isEqualTo("smoke tests failed");
        assertThat(entity.getSubmittedIp()).isEqualTo("203.0.113.7");
        assertThat(entity.getVersionLock()).isEqualTo(3L);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new DeploymentRequestEntity();

        assertThat(entity.getStatus()).isEqualTo(QueryStatus.PENDING_AI);
        assertThat(entity.getSubmissionReason()).isEqualTo(SubmissionReason.USER_SUBMITTED);
        assertThat(entity.getMetadata()).isEqualTo("{}");
        assertThat(entity.getRequiredApprovals()).isEqualTo(1);
        assertThat(entity.getOutcome()).isNull();
        assertThat(entity.getVersionLock()).isZero();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new DeploymentRequestEntity();
        entity.setUpdatedAt(Instant.EPOCH);
        entity.onUpdate();
        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
