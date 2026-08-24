package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestSubmissionResult;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewDecisionView;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentSubmittedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Submission, listing and cancellation of governed deployment requests (#691). Mirrors
 * {@code apigov}'s {@code DefaultApiRequestService}, with two deployment-specific twists: the
 * trigger is idempotent on the CI run identity, and an active {@code REJECT} freeze window
 * auto-rejects at submission.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentRequestService implements DeploymentRequestService {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentReviewDecisionRepository decisionRepository;
    private final EffectiveDeploymentPermissionResolver permissionResolver;
    private final FreezeWindowEvaluator freezeWindowEvaluator;
    private final DeploymentRequestStateService stateService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final UserQueryService userQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public DeploymentRequestSubmissionResult submit(SubmitDeploymentRequestCommand command) {
        var pipeline = pipelineRepository
                .findByIdAndOrganizationId(command.pipelineId(), command.organizationId())
                .orElseThrow(() -> new DeploymentPipelineNotFoundException(command.pipelineId()));
        if (!pipeline.isActive()) {
            throw new DeploymentPipelineNotFoundException(command.pipelineId());
        }
        var environment = environmentRepository
                .findByPipelineIdAndNameIgnoreCase(pipeline.getId(), command.environment())
                .orElseThrow(() -> new DeploymentEnvironmentNotFoundException(
                        pipeline.getId(), command.environment()));

        // Permission is enforced before the replay lookup so a caller without a grant cannot use a
        // repeated trigger to probe whether a given (pipeline, environment, version, run) exists.
        enforceTriggerPermission(pipeline, command);
        var replay = findReplay(pipeline.getId(), environment.getId(), command);
        if (replay != null) {
            return new DeploymentRequestSubmissionResult(toDetailView(replay), true);
        }

        var entity = newRequest(pipeline, environment, command);
        try {
            requestRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent trigger of the same CI run won the partial unique index. Return its row.
            var winner = findReplay(pipeline.getId(), environment.getId(), command);
            if (winner == null) {
                throw ex;
            }
            return new DeploymentRequestSubmissionResult(toDetailView(winner), true);
        }

        var freeze = freezeWindowEvaluator
                .evaluate(command.organizationId(), pipeline.getId(), environment.getId())
                .orElse(null);
        if (freeze != null && freeze.behavior() == FreezeBehavior.REJECT) {
            // HOLD deliberately does NOT block submission — it gates releasability at the gate (#693).
            stateService.apply(entity, QueryStatus.REJECTED);
            eventPublisher.publishEvent(new DeploymentDecidedEvent(entity.getId(),
                    QueryStatus.REJECTED, "freeze:" + freeze.windowId()));
            return new DeploymentRequestSubmissionResult(toDetailView(entity), false);
        }

        eventPublisher.publishEvent(new DeploymentSubmittedEvent(entity.getId()));
        return new DeploymentRequestSubmissionResult(toDetailView(entity), false);
    }

    /**
     * The request this trigger already created, or null. Only requests carrying an
     * {@code externalRunId} are deduplicated — that is exactly the partial unique index's scope.
     */
    private DeploymentRequestEntity findReplay(UUID pipelineId, UUID environmentId,
                                               SubmitDeploymentRequestCommand command) {
        if (command.externalRunId() == null || command.externalRunId().isBlank()) {
            return null;
        }
        return requestRepository.findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(
                pipelineId, environmentId, command.version(), command.externalRunId()).orElse(null);
    }

    private void enforceTriggerPermission(DeploymentPipelineEntity pipeline,
                                          SubmitDeploymentRequestCommand command) {
        if (command.admin()) {
            return;
        }
        var permission = permissionResolver.resolve(pipeline.getId(), command.submitterUserId())
                .orElse(null);
        if (permission == null || !permission.canTrigger()) {
            throw new DeploymentRequestPermissionException(
                    "No active trigger permission on this deployment pipeline");
        }
    }

    private DeploymentRequestEntity newRequest(DeploymentPipelineEntity pipeline,
                                               DeploymentEnvironmentEntity environment,
                                               SubmitDeploymentRequestCommand command) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(pipeline.getId());
        entity.setEnvironmentId(environment.getId());
        entity.setOrganizationId(command.organizationId());
        entity.setSubmittedBy(command.submitterUserId());
        entity.setVersion(command.version());
        entity.setCommitSha(command.commitSha());
        entity.setArtifactRef(command.artifactRef());
        entity.setRunUrl(command.runUrl());
        entity.setExternalRunId(blankToNull(command.externalRunId()));
        entity.setMetadata(objectMapper.writeValueAsString(command.metadata()));
        entity.setJustification(command.justification());
        entity.setScheduledFor(command.scheduledFor());
        entity.setSubmissionReason(command.submissionReason() != null
                ? command.submissionReason() : SubmissionReason.USER_SUBMITTED);
        entity.setSubmittedIp(command.submittedIp());
        entity.setStatus(QueryStatus.PENDING_AI);
        // A provisional count so the row is never inconsistent; the state machine resolves the real
        // one once routing and the review plan have been applied.
        entity.setRequiredApprovals(Math.max(1, environment.getRequiredApprovals() != null
                ? environment.getRequiredApprovals() : 1));
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeploymentRequestView> list(DeploymentRequestListFilter filter,
                                                    PageRequest pageRequest) {
        List<UUID> environmentIds = filter.environment() == null || filter.environment().isBlank()
                ? List.of()
                : environmentRepository.findIdsByOrganizationIdAndNameIgnoreCase(
                        filter.organizationId(), filter.environment().trim());
        var page = requestRepository.findAll(
                DeploymentRequestSpecifications.forFilter(filter, environmentIds),
                toPageable(pageRequest));
        return toPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentRequestView get(UUID id, UUID organizationId, UUID userId,
                                     Set<Permission> callerPermissions) {
        var entity = require(id, organizationId);
        if (!entity.getSubmittedBy().equals(userId) && !canViewAll(callerPermissions)) {
            // 404 rather than 403 — the endpoint must not confirm that an id exists.
            throw new DeploymentRequestNotFoundException(id);
        }
        return toDetailView(entity);
    }

    @Override
    @Transactional
    public void cancel(UUID id, UUID organizationId, UUID userId) {
        var entity = require(id, organizationId);
        if (!entity.getSubmittedBy().equals(userId)) {
            throw new DeploymentRequestPermissionException(
                    "Only the submitter can cancel a deployment request");
        }
        boolean cancellable = entity.getStatus() == QueryStatus.PENDING_REVIEW
                || (entity.getStatus() == QueryStatus.APPROVED && entity.getScheduledFor() != null);
        if (!cancellable) {
            throw new IllegalDeploymentRequestStateException(entity.getStatus(),
                    "Deployment request cannot be cancelled");
        }
        stateService.apply(entity, QueryStatus.CANCELLED);
    }

    private static boolean canViewAll(Set<Permission> callerPermissions) {
        return callerPermissions != null
                && (callerPermissions.contains(Permission.DEPLOYMENT_REVIEW)
                        || callerPermissions.contains(Permission.QUERY_ADMIN));
    }

    private DeploymentRequestEntity require(UUID id, UUID organizationId) {
        return requestRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new DeploymentRequestNotFoundException(id));
    }

    private PageResponse<DeploymentRequestView> toPage(Page<DeploymentRequestEntity> page) {
        var content = page.getContent().stream().map(e -> toView(e, List.of())).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize() <= 0 ? 1 : page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private DeploymentRequestView toDetailView(DeploymentRequestEntity entity) {
        var decisions = decisionRepository
                .findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(entity.getId()).stream()
                .map(d -> new DeploymentReviewDecisionView(d.getId(), d.getReviewerId(),
                        d.getDecision(), d.getComment(), d.getStage(), d.getDecidedAt()))
                .toList();
        return toView(entity, decisions);
    }

    private DeploymentRequestView toView(DeploymentRequestEntity entity,
                                         List<DeploymentReviewDecisionView> decisions) {
        var pipeline = pipelineRepository.findById(entity.getPipelineId()).orElse(null);
        var environment = environmentRepository.findById(entity.getEnvironmentId()).orElse(null);
        var analysis = entity.getAiAnalysisId() == null ? null
                : aiAnalysisLookupService.findById(entity.getAiAnalysisId()).orElse(null);
        var submitter = userQueryService.findById(entity.getSubmittedBy()).orElse(null);
        return new DeploymentRequestView(
                entity.getId(),
                entity.getPipelineId(),
                pipeline != null ? pipeline.getName() : null,
                pipeline != null ? pipeline.getProvider() : null,
                entity.getEnvironmentId(),
                environment != null ? environment.getName() : null,
                entity.getSubmittedBy(),
                submitter != null ? submitter.email() : null,
                entity.getVersion(),
                entity.getCommitSha(),
                entity.getArtifactRef(),
                entity.getRunUrl(),
                entity.getExternalRunId(),
                readMetadata(entity.getMetadata()),
                entity.getStatus(),
                entity.getSubmissionReason(),
                entity.getJustification(),
                entity.getAiAnalysisId(),
                analysis != null ? analysis.riskLevel() : null,
                analysis != null ? analysis.riskScore() : null,
                analysis != null ? analysis.summary() : null,
                entity.getRequiredApprovals(),
                entity.getScheduledFor(),
                entity.getOutcome(),
                entity.getOutcomeReportedAt(),
                entity.getOutcomeDetail(),
                entity.getCreatedAt(),
                decisions);
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (RuntimeException ex) {
            // Persisted metadata is submitter-authored; a malformed blob must not break the read.
            return Map.of();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size());
    }
}
