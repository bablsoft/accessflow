package com.bablsoft.accessflow.core.internal.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPredictionEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new ApprovalPredictionEntity();
        var id = UUID.randomUUID();
        var modelId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        var now = Instant.now();

        entity.setId(id);
        entity.setQueryRequest(queryRequest);
        entity.setProbability(0.78);
        entity.setModelId(modelId);
        entity.setFeatureSchemaVersion(1);
        entity.setFeatures("{\"risk_score\":42}");
        entity.setSkipped(true);
        entity.setSkippedReason("not enough history");
        entity.setFailed(true);
        entity.setErrorMessage("boom");
        entity.setCreatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getQueryRequest()).isSameAs(queryRequest);
        assertThat(entity.getProbability()).isEqualTo(0.78);
        assertThat(entity.getModelId()).isEqualTo(modelId);
        assertThat(entity.getFeatureSchemaVersion()).isEqualTo(1);
        assertThat(entity.getFeatures()).isEqualTo("{\"risk_score\":42}");
        assertThat(entity.isSkipped()).isTrue();
        assertThat(entity.getSkippedReason()).isEqualTo("not enough history");
        assertThat(entity.isFailed()).isTrue();
        assertThat(entity.getErrorMessage()).isEqualTo("boom");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
    }
}
