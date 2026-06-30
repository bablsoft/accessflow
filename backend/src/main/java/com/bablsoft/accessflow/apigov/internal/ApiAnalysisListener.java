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
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Drives async, fail-safe AI risk scoring of a submitted API call. On success it persists the
 * analysis (keyed to the API request, so token budgeting stays unified) and publishes
 * {@link ApiAnalysisCompletedEvent}; when the connector has AI disabled it publishes
 * {@link ApiAnalysisSkippedEvent}; any provider error publishes {@link ApiAnalysisFailedEvent} so the
 * request escalates to human review and is never blocked.
 */
@Component
@RequiredArgsConstructor
class ApiAnalysisListener {

    private static final Logger log = LoggerFactory.getLogger(ApiAnalysisListener.class);

    private final ApiRequestRepository requestRepository;
    private final ApiConnectorRepository connectorRepository;
    private final ApiSchemaService schemaService;
    private final ApiCallAnalyzer apiCallAnalyzer;
    private final AiAnalysisPersistenceService persistenceService;
    private final ApiConnectorClassificationRiskBooster classificationRiskBooster;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @ApplicationModuleListener
    void onSubmitted(ApiRequestSubmittedEvent event) {
        var request = requestRepository.findById(event.apiRequestId()).orElse(null);
        if (request == null) {
            return;
        }
        var connector = connectorRepository.findById(request.getConnectorId()).orElse(null);
        if (connector == null || !connector.isAiAnalysisEnabled() || connector.getAiConfigId() == null) {
            eventPublisher.publishEvent(new ApiAnalysisSkippedEvent(request.getId(), "ai_disabled"));
            return;
        }
        try {
            var schemaContext = renderSchema(connector, request.getOrganizationId());
            var analyzed = apiCallAnalyzer.analyzeApiCall(new ApiCallAnalyzer.ApiCallAnalysisInput(
                    request.getOrganizationId(), connector.getAiConfigId(), connector.getProtocol().name(),
                    request.getVerb(), request.getRequestPath(), request.getRequestBody(),
                    schemaContext, null));
            // AF-518: raise risk when the called operation references a data-classified field.
            var result = classificationRiskBooster.boost(analyzed, request.getOrganizationId(),
                    connector.getId(), request.getOperationId());
            var analysisId = persistenceService.persistForApiRequest(request.getId(), toCommand(result));
            request.setAiAnalysisId(analysisId);
            requestRepository.save(request);
            eventPublisher.publishEvent(new ApiAnalysisCompletedEvent(request.getId(), analysisId,
                    result.riskLevel(), result.riskScore(), result.summary()));
        } catch (AiAnalysisException ex) {
            log.warn("AI analysis failed for API request {}: {}", request.getId(), ex.getMessage());
            eventPublisher.publishEvent(new ApiAnalysisFailedEvent(request.getId(), ex.getMessage()));
        }
    }

    private String renderSchema(ApiConnectorEntity connector, UUID organizationId) {
        try {
            var ops = schemaService.listOperations(connector.getId(), organizationId);
            if (ops.isEmpty()) {
                return null;
            }
            var sb = new StringBuilder();
            for (var op : ops) {
                sb.append(op.verb()).append(' ').append(op.path())
                        .append(op.write() ? " [write]" : " [read]").append('\n');
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private PersistAiAnalysisCommand toCommand(AiAnalysisResult result) {
        return new PersistAiAnalysisCommand(result.aiProvider(), result.aiModel(), result.riskScore(),
                result.riskLevel(), result.summary(), objectMapper.writeValueAsString(result.issues()),
                objectMapper.writeValueAsString(result.optimizations()), result.missingIndexesDetected(),
                result.affectsRowEstimate(), result.promptTokens(), result.completionTokens(), false, null);
    }
}
