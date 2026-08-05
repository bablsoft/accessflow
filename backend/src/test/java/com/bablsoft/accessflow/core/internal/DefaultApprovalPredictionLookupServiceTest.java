package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.entity.ApprovalPredictionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ApprovalPredictionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApprovalPredictionLookupServiceTest {

    @Mock ApprovalPredictionRepository approvalPredictionRepository;
    @InjectMocks DefaultApprovalPredictionLookupService service;

    private static ApprovalPredictionEntity entity(UUID queryRequestId) {
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        var entity = new ApprovalPredictionEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(queryRequest);
        entity.setProbability(0.78);
        entity.setModelId(UUID.randomUUID());
        entity.setFeatureSchemaVersion(1);
        entity.setFeatures("{\"risk_score\":42}");
        entity.setSkipped(false);
        entity.setSkippedReason(null);
        entity.setFailed(false);
        entity.setErrorMessage(null);
        entity.setCreatedAt(Instant.parse("2026-08-01T09:00:00Z"));
        return entity;
    }

    @Test
    void lookupMapsEntityToSnapshot() {
        var queryRequestId = UUID.randomUUID();
        var entity = entity(queryRequestId);
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(entity));

        var snapshot = service.findByQueryRequestId(queryRequestId);

        assertThat(snapshot).hasValueSatisfying(s -> {
            assertThat(s.id()).isEqualTo(entity.getId());
            assertThat(s.queryRequestId()).isEqualTo(queryRequestId);
            assertThat(s.probability()).isEqualTo(0.78);
            assertThat(s.modelId()).isEqualTo(entity.getModelId());
            assertThat(s.featureSchemaVersion()).isEqualTo(1);
            assertThat(s.featuresJson()).isEqualTo("{\"risk_score\":42}");
            assertThat(s.skipped()).isFalse();
            assertThat(s.skippedReason()).isNull();
            assertThat(s.failed()).isFalse();
            assertThat(s.errorMessage()).isNull();
            assertThat(s.createdAt()).isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
        });
    }

    @Test
    void lookupEmptyWhenNoRow() {
        var queryRequestId = UUID.randomUUID();
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty());

        assertThat(service.findByQueryRequestId(queryRequestId)).isEmpty();
    }

    @Test
    void batchLookupMapsAllRows() {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var ids = List.of(firstId, secondId);
        when(approvalPredictionRepository.findByQueryRequestIdIn(ids))
                .thenReturn(List.of(entity(firstId), entity(secondId)));

        var snapshots = service.findByQueryRequestIds(ids);

        assertThat(snapshots).extracting("queryRequestId").containsExactly(firstId, secondId);
    }

    @Test
    void batchLookupShortCircuitsNullAndEmptyInput() {
        assertThat(service.findByQueryRequestIds(null)).isEmpty();
        assertThat(service.findByQueryRequestIds(List.of())).isEmpty();
        verifyNoInteractions(approvalPredictionRepository);
    }
}
