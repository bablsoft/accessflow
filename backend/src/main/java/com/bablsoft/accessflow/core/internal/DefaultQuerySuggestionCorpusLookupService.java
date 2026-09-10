package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusLookupService;
import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusRow;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bounded read of the suggestion mining corpus (#776), following the
 * {@code DefaultApprovalOutcomeHistoryLookupService} shape: one capped page per datasource rather
 * than an unbounded result list, so a decade of history costs the same as a month.
 */
@Service
@RequiredArgsConstructor
class DefaultQuerySuggestionCorpusLookupService implements QuerySuggestionCorpusLookupService {

    private final QueryRequestRepository queryRequestRepository;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDatasourceIdsWithHistory(UUID organizationId, Instant since) {
        return queryRequestRepository.findSuggestionCorpusDatasourceIds(organizationId, since);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuerySuggestionCorpusRow> findCorpus(UUID datasourceId, Instant since,
                                                     int maxRows) {
        if (maxRows <= 0) {
            return List.of();
        }
        return queryRequestRepository
                .findSuggestionCorpusRows(datasourceId, since, PageRequest.of(0, maxRows))
                .stream()
                .map(DefaultQuerySuggestionCorpusLookupService::toRow)
                .toList();
    }

    /** Column order mirrors {@code QueryRequestRepository.findSuggestionCorpusRows} — keep in sync. */
    private static QuerySuggestionCorpusRow toRow(Object[] row) {
        return new QuerySuggestionCorpusRow(
                (UUID) row[0],
                (DbType) row[1],
                (String) row[2],
                (QueryType) row[3],
                (UUID) row[4],
                (Instant) row[5]);
    }
}
