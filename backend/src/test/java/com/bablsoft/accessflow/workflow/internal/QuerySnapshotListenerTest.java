package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QuerySnapshotListenerTest {

    @Mock QuerySnapshotService querySnapshotService;
    @InjectMocks QuerySnapshotListener listener;

    @Test
    void recordsSnapshotOnExecutedEvent() {
        var queryId = UUID.randomUUID();

        listener.onQueryExecuted(new QueryExecutedEvent(queryId, 5L, 12L, QueryStatus.EXECUTED));

        verify(querySnapshotService).recordOnExecution(queryId);
    }

    @Test
    void ignoresFailedExecution() {
        listener.onQueryExecuted(
                new QueryExecutedEvent(UUID.randomUUID(), null, 12L, QueryStatus.FAILED));

        verify(querySnapshotService, never()).recordOnExecution(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void swallowsServiceFailure() {
        var queryId = UUID.randomUUID();
        doThrow(new RuntimeException("boom")).when(querySnapshotService).recordOnExecution(queryId);

        assertThatCode(() -> listener.onQueryExecuted(
                new QueryExecutedEvent(queryId, 1L, 1L, QueryStatus.EXECUTED)))
                .doesNotThrowAnyException();
    }
}
