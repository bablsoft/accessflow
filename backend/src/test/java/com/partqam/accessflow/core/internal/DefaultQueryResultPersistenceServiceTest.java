package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.QueryResultPersistenceService.QueryResultSnapshot;
import com.partqam.accessflow.core.api.QueryResultPersistenceService.SaveResultCommand;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestResultEntity;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryResultPersistenceServiceTest {

    @Mock QueryRequestResultRepository repository;
    @InjectMocks DefaultQueryResultPersistenceService service;

    @Test
    void saveInsertsFreshEntityWhenNoneExists() {
        var queryId = UUID.randomUUID();
        when(repository.findById(queryId)).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        service.save(new SaveResultCommand(queryId, "[{\"name\":\"id\"}]", "[[1]]", 1L, false, 12));

        var captor = ArgumentCaptor.forClass(QueryRequestResultEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getQueryRequestId()).isEqualTo(queryId);
        assertThat(saved.getColumns()).isEqualTo("[{\"name\":\"id\"}]");
        assertThat(saved.getRows()).isEqualTo("[[1]]");
        assertThat(saved.getRowCount()).isEqualTo(1L);
        assertThat(saved.isTruncated()).isFalse();
        assertThat(saved.getDurationMs()).isEqualTo(12);
        assertThat(saved.getRecordedAt()).isNotNull();
    }

    @Test
    void saveOverwritesExistingEntityKeepingPrimaryKey() {
        var queryId = UUID.randomUUID();
        var existing = new QueryRequestResultEntity();
        existing.setQueryRequestId(queryId);
        existing.setColumns("[]");
        existing.setRows("[]");
        existing.setRowCount(0);
        when(repository.findById(queryId)).thenReturn(Optional.of(existing));
        when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        service.save(new SaveResultCommand(queryId, "[{\"name\":\"x\"}]",
                "[[42]]", 1L, true, 99));

        // The same entity instance is updated and saved.
        assertThat(existing.getColumns()).isEqualTo("[{\"name\":\"x\"}]");
        assertThat(existing.getRows()).isEqualTo("[[42]]");
        assertThat(existing.getRowCount()).isEqualTo(1L);
        assertThat(existing.isTruncated()).isTrue();
        assertThat(existing.getDurationMs()).isEqualTo(99);
    }

    @Test
    void findReturnsSnapshotWhenPresent() {
        var queryId = UUID.randomUUID();
        var entity = new QueryRequestResultEntity();
        entity.setQueryRequestId(queryId);
        entity.setColumns("[{\"name\":\"id\"}]");
        entity.setRows("[[1],[2]]");
        entity.setRowCount(2);
        entity.setTruncated(false);
        entity.setDurationMs(45);
        when(repository.findById(queryId)).thenReturn(Optional.of(entity));

        var snapshot = service.find(queryId).orElseThrow();

        assertThat(snapshot).isEqualTo(new QueryResultSnapshot(queryId,
                "[{\"name\":\"id\"}]", "[[1],[2]]", 2L, false, 45));
    }

    @Test
    void findReturnsEmptyWhenAbsent() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.find(id)).isEmpty();
    }
}
