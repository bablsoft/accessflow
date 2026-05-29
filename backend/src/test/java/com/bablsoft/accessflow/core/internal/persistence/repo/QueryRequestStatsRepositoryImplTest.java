package com.bablsoft.accessflow.core.internal.persistence.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRequestStatsRepositoryImplTest {

    @Mock EntityManager entityManager;
    @Mock Query query;

    @Test
    void emptyIdsShortCircuitsWithoutTouchingEntityManager() {
        var repo = new QueryRequestStatsRepositoryImpl(entityManager);

        assertThat(repo.aggregateByDatasource(List.of(), Instant.now())).isEmpty();
        verifyNoInteractions(entityManager);
    }

    @Test
    void nullIdsShortCircuitsWithoutTouchingEntityManager() {
        var repo = new QueryRequestStatsRepositoryImpl(entityManager);

        assertThat(repo.aggregateByDatasource(null, Instant.now())).isEmpty();
        verifyNoInteractions(entityManager);
    }

    @Test
    void mapsRowsAndPreservesNullPercentiles() {
        var withLatency = UUID.randomUUID();
        var withoutLatency = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{withLatency, 5L, 2L, 10.0, 40.0},
                new Object[]{withoutLatency, 1L, 0L, null, null}));
        var repo = new QueryRequestStatsRepositoryImpl(entityManager);

        var rows = repo.aggregateByDatasource(List.of(withLatency, withoutLatency), Instant.now());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).datasourceId()).isEqualTo(withLatency);
        assertThat(rows.get(0).queriesLast24h()).isEqualTo(5L);
        assertThat(rows.get(0).errorsLast24h()).isEqualTo(2L);
        assertThat(rows.get(0).executionMsP50()).isEqualTo(10.0);
        assertThat(rows.get(0).executionMsP95()).isEqualTo(40.0);
        assertThat(rows.get(1).executionMsP50()).isNull();
        assertThat(rows.get(1).executionMsP95()).isNull();
    }
}
