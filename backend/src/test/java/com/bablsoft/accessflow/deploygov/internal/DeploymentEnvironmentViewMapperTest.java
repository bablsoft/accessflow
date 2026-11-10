package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationConfigCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentEnvironmentViewMapperTest {

    private final DeploymentVerificationConfigCodec codec =
            new DeploymentVerificationConfigCodec(JsonMapper.builder().build());

    @Test
    void copiesEveryColumnIncludingTheDatasourceBinding() {
        var e = new DeploymentEnvironmentEntity();
        e.setId(UUID.randomUUID());
        e.setPipelineId(UUID.randomUUID());
        e.setName("production");
        e.setSortOrder(3);
        e.setRequireReview(false);
        e.setRequiredApprovals(2);
        e.setReviewPlanId(UUID.randomUUID());
        e.setAllowBreakGlass(true);
        e.setTags(new String[]{"acme", "eu"});
        e.setDatasourceId(UUID.randomUUID());
        e.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));

        var view = DeploymentEnvironmentViewMapper.toView(e, codec);

        assertThat(view.id()).isEqualTo(e.getId());
        assertThat(view.pipelineId()).isEqualTo(e.getPipelineId());
        assertThat(view.name()).isEqualTo("production");
        assertThat(view.sortOrder()).isEqualTo(3);
        assertThat(view.requireReview()).isFalse();
        assertThat(view.requiredApprovals()).isEqualTo(2);
        assertThat(view.reviewPlanId()).isEqualTo(e.getReviewPlanId());
        assertThat(view.allowBreakGlass()).isTrue();
        assertThat(view.tags()).containsExactly("acme", "eu");
        assertThat(view.datasourceId()).isEqualTo(e.getDatasourceId());
        assertThat(view.createdAt()).isEqualTo(e.getCreatedAt());
        assertThat(view.verification()).isNull();
    }

    @Test
    void nullTagsAndDatasourceReadAsEmptyAndNull() {
        var e = new DeploymentEnvironmentEntity();
        e.setId(UUID.randomUUID());
        e.setPipelineId(UUID.randomUUID());
        e.setName("dev");
        e.setTags(null);

        var view = DeploymentEnvironmentViewMapper.toView(e, codec);

        assertThat(view.tags()).isEmpty();
        assertThat(view.datasourceId()).isNull();
    }

    @Test
    void decodesTheStoredVerificationConfig() {
        var config = new DeploymentVerificationConfig(Duration.ofMinutes(10), Duration.ofMinutes(1),
                2, List.of(new HttpHealthCheck("health", "https://svc/healthz", 200, null)));
        var e = new DeploymentEnvironmentEntity();
        e.setId(UUID.randomUUID());
        e.setVerification(codec.toJson(config));

        assertThat(DeploymentEnvironmentViewMapper.toView(e, codec).verification()).isEqualTo(config);
    }

    @Test
    void unreadableVerificationConfigMapsToNull() {
        var e = new DeploymentEnvironmentEntity();
        e.setId(UUID.randomUUID());
        e.setVerification("{\"checks\":[{\"kind\":\"DATADOG\"}]}");

        assertThat(DeploymentEnvironmentViewMapper.toView(e, codec).verification()).isNull();
    }
}
