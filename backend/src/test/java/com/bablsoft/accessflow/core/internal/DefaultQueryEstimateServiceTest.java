package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.PersistQueryEstimateCommand;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryEstimateEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryEstimateRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryEstimateServiceTest {

    @Mock QueryEstimateRepository queryEstimateRepository;
    @Mock QueryRequestRepository queryRequestRepository;
    @InjectMocks DefaultQueryEstimateService service;

    private static PersistQueryEstimateCommand command() {
        return new PersistQueryEstimateCommand("postgresql", QueryType.DELETE, true, 120L, 90L,
                "Seq Scan", 44.5, "{\"operation\":\"Seq Scan\"}", "[raw]", null, false, null, 12);
    }

    @Test
    void persistInsertsEstimateAndLinksQueryRequest() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        when(queryEstimateRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty());
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));
        when(queryEstimateRepository.save(any(QueryEstimateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var id = service.persist(queryRequestId, command());

        var captor = ArgumentCaptor.forClass(QueryEstimateEntity.class);
        verify(queryEstimateRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getQueryRequest()).isSameAs(queryRequest);
        assertThat(saved.getEngineId()).isEqualTo("postgresql");
        assertThat(saved.getQueryType()).isEqualTo(QueryType.DELETE);
        assertThat(saved.isSupported()).isTrue();
        assertThat(saved.getEstimatedRows()).isEqualTo(120L);
        assertThat(saved.getAffectedRowCount()).isEqualTo(90L);
        assertThat(saved.getScanType()).isEqualTo("Seq Scan");
        assertThat(saved.getEstimatedCost()).isEqualTo(44.5);
        assertThat(saved.getPlan()).isEqualTo("{\"operation\":\"Seq Scan\"}");
        assertThat(saved.getRawPlan()).isEqualTo("[raw]");
        assertThat(saved.isFailed()).isFalse();
        assertThat(saved.getDurationMs()).isEqualTo(12);
        assertThat(queryRequest.getQueryEstimateId()).isEqualTo(id);
    }

    @Test
    void persistReturnsExistingRowIdWhenRaced() {
        var queryRequestId = UUID.randomUUID();
        var existing = new QueryEstimateEntity();
        existing.setId(UUID.randomUUID());
        when(queryEstimateRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(existing));

        var id = service.persist(queryRequestId, command());

        assertThat(id).isEqualTo(existing.getId());
        verify(queryEstimateRepository, never()).save(any());
    }

    @Test
    void persistThrowsWhenQueryRequestMissing() {
        var queryRequestId = UUID.randomUUID();
        when(queryEstimateRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty());
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.persist(queryRequestId, command()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lookupMapsEntityToSnapshot() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        var entity = new QueryEstimateEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(queryRequest);
        entity.setEngineId("postgresql");
        entity.setQueryType(QueryType.UPDATE);
        entity.setSupported(true);
        entity.setEstimatedRows(10L);
        entity.setAffectedRowCount(8L);
        entity.setScanType("Index Scan");
        entity.setEstimatedCost(1.5);
        entity.setPlan("{}");
        entity.setRawPlan("raw");
        entity.setUnsupportedReason(null);
        entity.setFailed(false);
        entity.setErrorMessage(null);
        entity.setDurationMs(4);
        entity.setCreatedAt(Instant.parse("2026-07-22T10:00:00Z"));
        when(queryEstimateRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(entity));

        var snapshot = service.findByQueryRequestId(queryRequestId);

        assertThat(snapshot).hasValueSatisfying(s -> {
            assertThat(s.id()).isEqualTo(entity.getId());
            assertThat(s.queryRequestId()).isEqualTo(queryRequestId);
            assertThat(s.engineId()).isEqualTo("postgresql");
            assertThat(s.queryType()).isEqualTo(QueryType.UPDATE);
            assertThat(s.supported()).isTrue();
            assertThat(s.estimatedRows()).isEqualTo(10L);
            assertThat(s.affectedRowCount()).isEqualTo(8L);
            assertThat(s.scanType()).isEqualTo("Index Scan");
            assertThat(s.estimatedCost()).isEqualTo(1.5);
            assertThat(s.planJson()).isEqualTo("{}");
            assertThat(s.rawPlan()).isEqualTo("raw");
            assertThat(s.failed()).isFalse();
            assertThat(s.durationMs()).isEqualTo(4);
            assertThat(s.createdAt()).isEqualTo(Instant.parse("2026-07-22T10:00:00Z"));
        });
    }

    @Test
    void lookupEmptyWhenNoRow() {
        var queryRequestId = UUID.randomUUID();
        when(queryEstimateRepository.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty());

        assertThat(service.findByQueryRequestId(queryRequestId)).isEmpty();
    }
}
