package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.QueryResultPersistenceService;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestResultEntity;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryResultPersistenceService implements QueryResultPersistenceService {

    private final QueryRequestResultRepository repository;

    @Override
    @Transactional
    public void save(SaveResultCommand command) {
        var entity = repository.findById(command.queryRequestId())
                .orElseGet(() -> {
                    var fresh = new QueryRequestResultEntity();
                    fresh.setQueryRequestId(command.queryRequestId());
                    return fresh;
                });
        entity.setColumns(command.columnsJson());
        entity.setRows(command.rowsJson());
        entity.setRowCount(command.rowCount());
        entity.setTruncated(command.truncated());
        entity.setDurationMs(command.durationMs());
        entity.setRecordedAt(Instant.now());
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QueryResultSnapshot> find(UUID queryRequestId) {
        return repository.findById(queryRequestId).map(e -> new QueryResultSnapshot(
                e.getQueryRequestId(),
                e.getColumns(),
                e.getRows(),
                e.getRowCount(),
                e.isTruncated(),
                e.getDurationMs()));
    }
}
