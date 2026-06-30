package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestSubmittedEvent;
import com.bablsoft.accessflow.lifecycle.events.ErasureScopeAnalyzedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Async erasure scope detection (AF-499). On {@link ErasureRequestSubmittedEvent} (fired after the
 * submit commits), it computes the set of tables linked to the subject — currently derived
 * deterministically from the datasource's enabled retention policies — captures an immutable scope
 * snapshot, and advances the request to {@link ErasureStatus#PENDING_REVIEW}. This is the plug-in
 * point for the AI-assisted scope detection (a future {@code AiAnalyzerStrategy.analyzeErasureScope}
 * enrichment); the pipeline is fully fail-safe — an analysis failure still advances the request to
 * review with whatever scope could be computed, never blocking it.
 */
@Component
@RequiredArgsConstructor
class ErasureScopeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ErasureScopeAnalyzer.class);

    private final DeletionRequestRepository requestRepository;
    private final RetentionPolicyRepository policyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @ApplicationModuleListener
    void onSubmitted(ErasureRequestSubmittedEvent event) {
        var entity = requestRepository.findByIdForUpdate(event.requestId()).orElse(null);
        if (entity == null || entity.getStatus() != ErasureStatus.PENDING_SCOPE_AI) {
            return;
        }
        try {
            entity.setScopeSnapshot(buildScopeSnapshot(entity));
        } catch (RuntimeException ex) {
            log.error("Erasure scope detection failed for request {}", event.requestId(), ex);
        }
        entity.setStatus(ErasureStatus.PENDING_REVIEW);
        requestRepository.save(entity);
        long estimated = entity.getEstimatedRows() == null ? 0 : entity.getEstimatedRows();
        eventPublisher.publishEvent(new ErasureScopeAnalyzedEvent(
                entity.getId(), entity.getOrganizationId(), estimated));
    }

    private String buildScopeSnapshot(DeletionRequestEntity entity) {
        Set<String> tables = new LinkedHashSet<>();
        for (RetentionPolicyEntity policy
                : policyRepository.findAllByDatasourceIdAndEnabledTrue(entity.getDatasourceId())) {
            if (policy.getTargetTable() != null && !policy.getTargetTable().isBlank()) {
                tables.add(policy.getTargetTable());
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("subjectType", entity.getSubjectType().name());
        root.put("subjectIdentifier", entity.getSubjectIdentifier());
        ArrayNode tableArray = root.putArray("tables");
        tables.forEach(tableArray::add);
        return objectMapper.writeValueAsString(root);
    }
}
