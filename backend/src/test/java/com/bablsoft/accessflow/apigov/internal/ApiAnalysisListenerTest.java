package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.ApiCallAnalyzer;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.events.ApiAnalysisCompletedEvent;
import com.bablsoft.accessflow.apigov.events.ApiAnalysisFailedEvent;
import com.bablsoft.accessflow.apigov.events.ApiAnalysisSkippedEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestSubmittedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiAnalysisListenerTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiSchemaService schemaService;
    @Mock private ApiCallAnalyzer apiCallAnalyzer;
    @Mock private AiAnalysisPersistenceService persistenceService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ApiAnalysisListener listener;

    private final UUID requestId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new ApiAnalysisListener(requestRepository, connectorRepository, schemaService,
                apiCallAnalyzer, persistenceService, eventPublisher, JsonMapper.builder().build());
        lenient().when(schemaService.listOperations(any(), any())).thenReturn(List.of());
    }

    private ApiRequestEntity request() {
        var e = new ApiRequestEntity();
        e.setId(requestId);
        e.setConnectorId(connectorId);
        e.setOrganizationId(UUID.randomUUID());
        e.setVerb("POST");
        e.setRequestPath("/charges");
        return e;
    }

    private ApiConnectorEntity connector(boolean aiEnabled, UUID aiConfigId) {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setProtocol(ApiProtocol.REST);
        c.setAiAnalysisEnabled(aiEnabled);
        c.setAiConfigId(aiConfigId);
        return c;
    }

    @Test
    void unknownRequestIsIgnored() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());
        listener.onSubmitted(new ApiRequestSubmittedEvent(requestId));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aiDisabledPublishesSkipped() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(false, null)));

        listener.onSubmitted(new ApiRequestSubmittedEvent(requestId));

        verify(eventPublisher).publishEvent(any(ApiAnalysisSkippedEvent.class));
    }

    @Test
    void successPersistsAnalysisAndPublishesCompleted() {
        var entity = request();
        var aiConfigId = UUID.randomUUID();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(true, aiConfigId)));
        when(apiCallAnalyzer.analyzeApiCall(any())).thenReturn(new AiAnalysisResult(55, RiskLevel.HIGH,
                "risky", List.of(), false, null, AiProviderType.ANTHROPIC, "claude", 2, 3, List.of()));
        var analysisId = UUID.randomUUID();
        when(persistenceService.persistForApiRequest(any(), any())).thenReturn(analysisId);

        listener.onSubmitted(new ApiRequestSubmittedEvent(requestId));

        verify(persistenceService).persistForApiRequest(any(), any());
        verify(requestRepository).save(entity);
        verify(eventPublisher).publishEvent(any(ApiAnalysisCompletedEvent.class));
    }

    @Test
    void providerFailurePublishesFailed() {
        var aiConfigId = UUID.randomUUID();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(true, aiConfigId)));
        when(apiCallAnalyzer.analyzeApiCall(any())).thenThrow(new AiAnalysisException("provider down"));

        listener.onSubmitted(new ApiRequestSubmittedEvent(requestId));

        verify(eventPublisher).publishEvent(any(ApiAnalysisFailedEvent.class));
    }
}
