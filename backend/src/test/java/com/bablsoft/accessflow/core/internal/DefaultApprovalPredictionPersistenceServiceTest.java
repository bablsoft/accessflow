package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.PersistApprovalPredictionCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.ApprovalPredictionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ApprovalPredictionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApprovalPredictionPersistenceServiceTest {

    @Mock ApprovalPredictionRepository approvalPredictionRepository;
    @Mock QueryRequestRepository queryRequestRepository;

    DefaultApprovalPredictionPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new DefaultApprovalPredictionPersistenceService(approvalPredictionRepository,
                queryRequestRepository, new ObjectMapper());
    }

    private static PersistApprovalPredictionCommand command(UUID queryRequestId) {
        return new PersistApprovalPredictionCommand(queryRequestId, 0.78, UUID.randomUUID(), 1,
                "{\"estimate_missing\":false,\"risk_score\":42}", false, null, false, null);
    }

    private static ApprovalPredictionEntity existingRow(String featuresJson) {
        var entity = new ApprovalPredictionEntity();
        entity.setId(UUID.randomUUID());
        entity.setProbability(0.10);
        entity.setFeatures(featuresJson);
        return entity;
    }

    @Test
    void persistInsertsPredictionWithAllCommandFields() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        var command = command(queryRequestId);
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty());
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));
        when(approvalPredictionRepository.save(any(ApprovalPredictionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var id = service.persist(command);

        var captor = ArgumentCaptor.forClass(ApprovalPredictionEntity.class);
        verify(approvalPredictionRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getQueryRequest()).isSameAs(queryRequest);
        assertThat(saved.getProbability()).isEqualTo(0.78);
        assertThat(saved.getModelId()).isEqualTo(command.modelId());
        assertThat(saved.getFeatureSchemaVersion()).isEqualTo(1);
        assertThat(saved.getFeatures()).isEqualTo(command.featuresJson());
        assertThat(saved.isSkipped()).isFalse();
        assertThat(saved.getSkippedReason()).isNull();
        assertThat(saved.isFailed()).isFalse();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void persistInsertsSkippedSentinelRow() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        var command = new PersistApprovalPredictionCommand(queryRequestId, null, null, null, null,
                true, "NOT_ENOUGH_HISTORY", false, null);
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty());
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));
        when(approvalPredictionRepository.save(any(ApprovalPredictionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.persist(command);

        var captor = ArgumentCaptor.forClass(ApprovalPredictionEntity.class);
        verify(approvalPredictionRepository).save(captor.capture());
        assertThat(captor.getValue().getProbability()).isNull();
        assertThat(captor.getValue().isSkipped()).isTrue();
        assertThat(captor.getValue().getSkippedReason()).isEqualTo("NOT_ENOUGH_HISTORY");
    }

    @Test
    void persistReturnsExistingIdUntouchedWhenSnapshotHadEstimate() {
        var queryRequestId = UUID.randomUUID();
        var existing = existingRow("{\"estimate_missing\":false}");
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(existing));

        var id = service.persist(command(queryRequestId));

        assertThat(id).isEqualTo(existing.getId());
        assertThat(existing.getProbability()).isEqualTo(0.10);
        verify(approvalPredictionRepository, never()).save(any());
    }

    @Test
    void persistTreatsNullFeaturesAsInsertOnce() {
        var queryRequestId = UUID.randomUUID();
        var existing = existingRow(null);
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(existing));

        var id = service.persist(command(queryRequestId));

        assertThat(id).isEqualTo(existing.getId());
        verify(approvalPredictionRepository, never()).save(any());
    }

    @Test
    void persistTreatsUnparseableFeaturesAsInsertOnce() {
        var queryRequestId = UUID.randomUUID();
        var existing = existingRow("not json");
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(existing));

        var id = service.persist(command(queryRequestId));

        assertThat(id).isEqualTo(existing.getId());
        verify(approvalPredictionRepository, never()).save(any());
    }

    @Test
    void persistReplacesRowWhoseSnapshotMissedTheEstimate() {
        var queryRequestId = UUID.randomUUID();
        var existing = existingRow("{\"estimate_missing\":true}");
        var command = command(queryRequestId);
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(existing));

        var id = service.persist(command);

        assertThat(id).isEqualTo(existing.getId());
        var captor = ArgumentCaptor.forClass(ApprovalPredictionEntity.class);
        verify(approvalPredictionRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getProbability()).isEqualTo(0.78);
        assertThat(saved.getModelId()).isEqualTo(command.modelId());
        assertThat(saved.getFeatureSchemaVersion()).isEqualTo(1);
        assertThat(saved.getFeatures()).isEqualTo(command.featuresJson());
    }

    @Test
    void persistThrowsWhenQueryRequestMissing() {
        var queryRequestId = UUID.randomUUID();
        when(approvalPredictionRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty());
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.persist(command(queryRequestId)))
                .isInstanceOf(IllegalStateException.class);
    }
}
