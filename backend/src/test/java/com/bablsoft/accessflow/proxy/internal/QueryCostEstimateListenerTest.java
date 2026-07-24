package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.proxy.api.QueryCostEstimateService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryCostEstimateListenerTest {

    @Test
    void onSubmittedTriggersEstimate() {
        var service = mock(QueryCostEstimateService.class);
        var queryRequestId = UUID.randomUUID();
        when(service.estimateSubmittedQuery(queryRequestId)).thenReturn(Optional.empty());

        new QueryCostEstimateListener(service)
                .onSubmitted(new QuerySubmittedEvent(queryRequestId));

        verify(service).estimateSubmittedQuery(queryRequestId);
    }
}
