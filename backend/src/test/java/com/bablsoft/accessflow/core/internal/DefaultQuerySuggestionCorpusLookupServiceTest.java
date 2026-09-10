package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQuerySuggestionCorpusLookupServiceTest {

    private static final Instant SINCE = Instant.parse("2026-06-12T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DATASOURCE = UUID.randomUUID();

    private QueryRequestRepository repository;
    private DefaultQuerySuggestionCorpusLookupService service;

    @BeforeEach
    void setUp() {
        repository = mock(QueryRequestRepository.class);
        service = new DefaultQuerySuggestionCorpusLookupService(repository);
    }

    @Test
    void datasourceIdsAreDelegatedUnchanged() {
        when(repository.findSuggestionCorpusDatasourceIds(ORG, SINCE))
                .thenReturn(List.of(DATASOURCE));

        assertThat(service.findDatasourceIdsWithHistory(ORG, SINCE)).containsExactly(DATASOURCE);
    }

    @Test
    void nonPositiveMaxRowsReadsNothing() {
        assertThat(service.findCorpus(DATASOURCE, SINCE, 0)).isEmpty();
        assertThat(service.findCorpus(DATASOURCE, SINCE, -1)).isEmpty();

        verify(repository, never()).findSuggestionCorpusRows(any(), any(), any());
    }

    @Test
    void maxRowsBecomesTheSinglePageSize() {
        when(repository.findSuggestionCorpusRows(eq(DATASOURCE), eq(SINCE), any()))
                .thenReturn(List.of());

        service.findCorpus(DATASOURCE, SINCE, 750);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findSuggestionCorpusRows(eq(DATASOURCE), eq(SINCE), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(750);
    }

    @Test
    void columnOrderMatchesTheRepositoryProjection() {
        var submittedAt = Instant.parse("2026-09-01T09:00:00Z");
        var submitter = UUID.randomUUID();
        when(repository.findSuggestionCorpusRows(eq(DATASOURCE), eq(SINCE), any()))
                .thenReturn(List.<Object[]>of(new Object[]{DATASOURCE, DbType.POSTGRESQL,
                        "select 1 from orders", QueryType.SELECT, submitter, submittedAt}));

        var rows = service.findCorpus(DATASOURCE, SINCE, 10);

        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.datasourceId()).isEqualTo(DATASOURCE);
        assertThat(row.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(row.sqlText()).isEqualTo("select 1 from orders");
        assertThat(row.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(row.submittedByUserId()).isEqualTo(submitter);
        assertThat(row.submittedAt()).isEqualTo(submittedAt);
    }
}
