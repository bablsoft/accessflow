package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentPipelineEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new DeploymentPipelineEntity();
        var id = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var reviewPlanId = UUID.randomUUID();
        var aiConfigId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-08-01T10:00:00Z");
        var updatedAt = Instant.parse("2026-08-02T10:00:00Z");

        entity.setId(id);
        entity.setOrganizationId(organizationId);
        entity.setName("payments-api");
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
        entity.setRepositoryUrl("https://github.com/acme/payments-api");
        entity.setProjectRef("acme/payments-api");
        entity.setReviewPlanId(reviewPlanId);
        entity.setAiAnalysisEnabled(false);
        entity.setAiConfigId(aiConfigId);
        entity.setActive(false);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(organizationId);
        assertThat(entity.getName()).isEqualTo("payments-api");
        assertThat(entity.getProvider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
        assertThat(entity.getRepositoryUrl()).isEqualTo("https://github.com/acme/payments-api");
        assertThat(entity.getProjectRef()).isEqualTo("acme/payments-api");
        assertThat(entity.getReviewPlanId()).isEqualTo(reviewPlanId);
        assertThat(entity.isAiAnalysisEnabled()).isFalse();
        assertThat(entity.getAiConfigId()).isEqualTo(aiConfigId);
        assertThat(entity.isActive()).isFalse();
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new DeploymentPipelineEntity();

        assertThat(entity.isAiAnalysisEnabled()).isTrue();
        assertThat(entity.isActive()).isTrue();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new DeploymentPipelineEntity();
        entity.setUpdatedAt(Instant.EPOCH);
        entity.onUpdate();
        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
