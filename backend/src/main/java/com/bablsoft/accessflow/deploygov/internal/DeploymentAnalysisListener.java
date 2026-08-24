package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.DeploymentAnalyzer;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisCompletedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisFailedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisSkippedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentSubmittedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives async, fail-safe AI risk scoring of a submitted deployment. On success it persists the
 * analysis (keyed to the deployment request, so token budgeting stays unified) and publishes
 * {@link DeploymentAnalysisCompletedEvent}; when the pipeline has AI disabled it publishes
 * {@link DeploymentAnalysisSkippedEvent}; any provider or parse error publishes
 * {@link DeploymentAnalysisFailedEvent} so the deployment escalates to human review and is never
 * blocked.
 *
 * <p>This listener lives in {@code deploygov} rather than in {@code ai} on purpose: governed-surface
 * modules depend on {@code ai.api}, never the reverse, so {@code ai} stays free of any knowledge of
 * deployments.
 */
@Component
@RequiredArgsConstructor
class DeploymentAnalysisListener {

    private static final Logger log = LoggerFactory.getLogger(DeploymentAnalysisListener.class);

    /** A CI job can put a whole changelog in {@code metadata}; the prompt takes a bounded slice. */
    static final int MAX_METADATA_CONTEXT_CHARS = 16_000;

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentAnalyzer deploymentAnalyzer;
    private final AiAnalysisPersistenceService persistenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @ApplicationModuleListener
    void onSubmitted(DeploymentSubmittedEvent event) {
        var request = requestRepository.findById(event.deploymentRequestId()).orElse(null);
        if (request == null) {
            return;
        }
        var pipeline = pipelineRepository.findById(request.getPipelineId()).orElse(null);
        if (pipeline == null || !pipeline.isAiAnalysisEnabled() || pipeline.getAiConfigId() == null) {
            eventPublisher.publishEvent(
                    new DeploymentAnalysisSkippedEvent(request.getId(), "ai_disabled"));
            return;
        }
        try {
            var environment = environmentRepository.findById(request.getEnvironmentId()).orElse(null);
            var result = deploymentAnalyzer.analyzeDeployment(
                    new DeploymentAnalyzer.DeploymentAnalysisInput(
                            request.getOrganizationId(), pipeline.getAiConfigId(),
                            pipeline.getProvider().name(), environmentName(environment),
                            request.getVersion(), request.getCommitSha(), request.getArtifactRef(),
                            renderMetadata(request), null));
            var analysisId = persistenceService.persistForDeploymentRequest(request.getId(),
                    toCommand(result));
            request.setAiAnalysisId(analysisId);
            requestRepository.save(request);
            eventPublisher.publishEvent(new DeploymentAnalysisCompletedEvent(request.getId(),
                    analysisId, result.riskLevel(), result.riskScore(), result.summary()));
        } catch (AiAnalysisException | AiAnalysisParseException ex) {
            // AiAnalysisParseException is NOT a subtype of AiAnalysisException — a strict-JSON
            // failure must land here too, never propagate out of the listener.
            log.warn("AI analysis failed for deployment request {}: {}", request.getId(),
                    ex.getMessage());
            eventPublisher.publishEvent(
                    new DeploymentAnalysisFailedEvent(request.getId(), ex.getMessage()));
        }
    }

    private static String environmentName(DeploymentEnvironmentEntity environment) {
        return environment != null ? environment.getName() : null;
    }

    /** The stored metadata jsonb as prompt context, size-capped. Null when it carries nothing. */
    private String renderMetadata(DeploymentRequestEntity request) {
        var raw = request.getMetadata();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return null;
        }
        return raw.length() <= MAX_METADATA_CONTEXT_CHARS
                ? raw
                : raw.substring(0, MAX_METADATA_CONTEXT_CHARS) + "\n… (truncated)";
    }

    private PersistAiAnalysisCommand toCommand(AiAnalysisResult result) {
        return new PersistAiAnalysisCommand(result.aiProvider(), result.aiModel(), result.riskScore(),
                result.riskLevel(), result.summary(), objectMapper.writeValueAsString(result.issues()),
                objectMapper.writeValueAsString(result.optimizations()), result.missingIndexesDetected(),
                result.affectsRowEstimate(), result.promptTokens(), result.completionTokens(), false, null);
    }
}
