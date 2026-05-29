package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceQueryStatsRow;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDatasourceQueryStatsLookupServiceTest {

    @Mock
    QueryRequestRepository queryRequestRepository;

    private DefaultDatasourceQueryStatsLookupService service() {
        return new DefaultDatasourceQueryStatsLookupService(queryRequestRepository);
    }

    private static DatasourceQueryStatsRow row(UUID id, long queries, long errors,
                                               Double p50, Double p95) {
        return new DatasourceQueryStatsRow(id, queries, errors, p50, p95);
    }

    @Test
    void returnsEmptyMapForNullIdsWithoutHittingRepository() {
        assertThat(service().statsFor(null, Instant.now())).isEmpty();
        verifyNoInteractions(queryRequestRepository);
    }

    @Test
    void returnsEmptyMapForEmptyIdsWithoutHittingRepository() {
        assertThat(service().statsFor(Set.of(), Instant.now())).isEmpty();
        verify(queryRequestRepository, never()).aggregateByDatasource(anyCollection(), any());
    }

    @Test
    void mapsProjectionsKeyedByDatasourceId() {
        var dsA = UUID.randomUUID();
        var dsB = UUID.randomUUID();
        var since = Instant.parse("2026-05-29T00:00:00Z");
        when(queryRequestRepository.aggregateByDatasource(anyCollection(), any()))
                .thenReturn(List.of(
                        row(dsA, 42L, 3L, 12.5, 88.0),
                        row(dsB, 5L, 0L, null, null)));

        var result = service().statsFor(List.of(dsA, dsB), since);

        assertThat(result).hasSize(2);
        assertThat(result.get(dsA).queriesLast24h()).isEqualTo(42L);
        assertThat(result.get(dsA).errorsLast24h()).isEqualTo(3L);
        assertThat(result.get(dsA).executionMsP50()).isEqualTo(12.5);
        assertThat(result.get(dsA).executionMsP95()).isEqualTo(88.0);
        assertThat(result.get(dsB).queriesLast24h()).isEqualTo(5L);
        assertThat(result.get(dsB).executionMsP50()).isNull();
        assertThat(result.get(dsB).executionMsP95()).isNull();
    }
}
