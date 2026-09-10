package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.workflow.api.QuerySuggestionAggregationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QuerySuggestionAggregationJobTest {

    @Test
    void runDelegatesToTheAggregationService() {
        var service = mock(QuerySuggestionAggregationService.class);

        new QuerySuggestionAggregationJob(service).run();

        verify(service).aggregateAll();
    }

    @Test
    void aFailureOutsideThePerOrganisationBoundaryNeverEscapesIntoTheScheduler() {
        var service = mock(QuerySuggestionAggregationService.class);
        doThrow(new IllegalStateException("organisation page read failed"))
                .when(service).aggregateAll();

        assertThatCode(() -> new QuerySuggestionAggregationJob(service).run())
                .doesNotThrowAnyException();
    }
}
