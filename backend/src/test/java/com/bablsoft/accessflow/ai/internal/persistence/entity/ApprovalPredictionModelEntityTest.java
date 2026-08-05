package com.bablsoft.accessflow.ai.internal.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPredictionModelEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new ApprovalPredictionModelEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var now = Instant.now();

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setFeatureSchemaVersion(1);
        entity.setCoefficients("{\"intercept\":0.5}");
        entity.setTrainingSamples(120);
        entity.setPositiveSamples(80);
        entity.setAuc(0.72);
        entity.setAccuracy(0.81);
        entity.setServing(true);
        entity.setTrainedAt(now);
        entity.setVersion(2L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getFeatureSchemaVersion()).isEqualTo(1);
        assertThat(entity.getCoefficients()).isEqualTo("{\"intercept\":0.5}");
        assertThat(entity.getTrainingSamples()).isEqualTo(120);
        assertThat(entity.getPositiveSamples()).isEqualTo(80);
        assertThat(entity.getAuc()).isEqualTo(0.72);
        assertThat(entity.getAccuracy()).isEqualTo(0.81);
        assertThat(entity.isServing()).isTrue();
        assertThat(entity.getTrainedAt()).isEqualTo(now);
        assertThat(entity.getVersion()).isEqualTo(2L);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new ApprovalPredictionModelEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
