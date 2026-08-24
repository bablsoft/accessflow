package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewerNotEligibleException;
import com.bablsoft.accessflow.deploygov.api.DeploymentSelfApprovalException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentReviewDecisionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Copies {@code apigov}'s {@code DefaultApiReviewService} semantics (#692): a single review stage,
 * idempotent {@code (request, reviewer, stage)} decisions, quorum counting against the request's
 * folded {@code required_approvals}, an immediate terminal reject, and the self-approval ban.
 * Approver eligibility is honoured <strong>opt-in by configuration</strong>: an environment whose
 * resolved plan (its own override, else the pipeline's) is absent or carries no approver rules
 * stays open to any holder of {@code DEPLOYMENT_REVIEW}. Review delegation (#622) is deliberately
 * not supported — {@code deployment_review_decisions} carries no delegation columns.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentReviewService implements DeploymentReviewService {

    private static final int STAGE = 1;

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentReviewDecisionRepository decisionRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentRequestStateService stateService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingDeploymentReview> listPending(ReviewerContext context,
                                                             PendingDeploymentReviewFilter filter,
                                                             PageRequest pageRequest) {
        if (!hasPermission(context, Permission.DEPLOYMENT_REVIEW)) {
            return PageResponse.empty(pageRequest.page(), pageRequest.size());
        }
        var unrestricted = hasPermission(context, Permission.REVIEW_OVERRIDE);
        var spec = DeploymentRequestSpecifications.forPendingReview(context.organizationId(),
                context.userId(), filter.pipelineId(), unrestricted,
                unrestricted ? Set.of() : reviewReach(context));
        var page = requestRepository.findAll(spec,
                org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size()));
        var content = page.getContent().stream().map(this::toPending).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize() <= 0 ? 1 : page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID deploymentRequestId, ReviewerContext context, String comment) {
        var request = require(deploymentRequestId, context.organizationId());
        guardReviewable(request, context);
        var existing = decisionRepository.findByDeploymentRequestIdAndReviewerIdAndStage(
                deploymentRequestId, context.userId(), STAGE);
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), existing.get().getDecision(),
                    request.getStatus(), true);
        }
        var decision = record(deploymentRequestId, context.userId(), DecisionType.APPROVED, comment);
        long approvals = decisionRepository.countByDeploymentRequestIdAndStageAndDecision(
                deploymentRequestId, STAGE, DecisionType.APPROVED);
        QueryStatus resulting = request.getStatus();
        if (approvals >= request.getRequiredApprovals()) {
            stateService.apply(request, QueryStatus.APPROVED);
            eventPublisher.publishEvent(new DeploymentDecidedEvent(deploymentRequestId,
                    QueryStatus.APPROVED, null));
            resulting = QueryStatus.APPROVED;
        }
        return new DecisionOutcome(decision.getId(), DecisionType.APPROVED, resulting, false);
    }

    @Override
    @Transactional
    public DecisionOutcome reject(UUID deploymentRequestId, ReviewerContext context, String comment) {
        var request = require(deploymentRequestId, context.organizationId());
        guardReviewable(request, context);
        var existing = decisionRepository.findByDeploymentRequestIdAndReviewerIdAndStage(
                deploymentRequestId, context.userId(), STAGE);
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), existing.get().getDecision(),
                    request.getStatus(), true);
        }
        var decision = record(deploymentRequestId, context.userId(), DecisionType.REJECTED, comment);
        stateService.apply(request, QueryStatus.REJECTED);
        eventPublisher.publishEvent(new DeploymentDecidedEvent(deploymentRequestId,
                QueryStatus.REJECTED, null));
        return new DecisionOutcome(decision.getId(), DecisionType.REJECTED, QueryStatus.REJECTED,
                false);
    }

    /**
     * Guard order mirrors the apigov sibling: self-approval, then state, then permission, then
     * {@code REVIEW_OVERRIDE}, then plan-approver eligibility. Enforced in the service, not only
     * via {@code @PreAuthorize} on the controller.
     */
    private void guardReviewable(DeploymentRequestEntity request, ReviewerContext context) {
        if (request.getSubmittedBy().equals(context.userId())) {
            throw new DeploymentSelfApprovalException();
        }
        if (request.getStatus() != QueryStatus.PENDING_REVIEW) {
            throw new IllegalDeploymentRequestStateException(request.getStatus(),
                    "Deployment request is not awaiting review");
        }
        if (!hasPermission(context, Permission.DEPLOYMENT_REVIEW)) {
            throw new DeploymentReviewerNotEligibleException(context.userId(), request.getId());
        }
        if (hasPermission(context, Permission.REVIEW_OVERRIDE)) {
            return;
        }
        var plan = resolvePlan(request.getPipelineId(), request.getEnvironmentId());
        if (plan == null || plan.approvers().isEmpty()) {
            return;
        }
        if (!isApproverAtStage(plan, STAGE, context)) {
            throw new DeploymentReviewerNotEligibleException(context.userId(), request.getId());
        }
    }

    /**
     * The environment ids the caller may review, pre-resolved for the queue specification —
     * deploygov cannot reference {@code core}'s approver entities in SQL. Every pipeline is
     * considered, inactive ones included: deactivating a pipeline must not make its
     * already-submitted {@code PENDING_REVIEW} requests vanish from the queue while the decision
     * path can still reach them.
     */
    private Set<UUID> reviewReach(ReviewerContext context) {
        var pipelines = pipelineRepository.findAllByOrganizationId(context.organizationId());
        var plans = new HashMap<UUID, ReviewPlanSnapshot>();
        var reachable = new HashSet<UUID>();
        for (var pipeline : pipelines) {
            for (var environment : environmentRepository
                    .findByPipelineIdOrderBySortOrderAscNameAsc(pipeline.getId())) {
                var plan = snapshot(resolvePlanId(pipeline, environment), plans);
                if (plan == null || plan.approvers().isEmpty()
                        || isApproverAtStage(plan, STAGE, context)) {
                    reachable.add(environment.getId());
                }
            }
        }
        return reachable;
    }

    private ReviewPlanSnapshot snapshot(UUID planId, Map<UUID, ReviewPlanSnapshot> cache) {
        if (planId == null) {
            return null;
        }
        return cache.computeIfAbsent(planId, id -> reviewPlanLookupService.findById(id).orElse(null));
    }

    /** The environment's plan override wins over the pipeline's — same rule as routing. */
    private ReviewPlanSnapshot resolvePlan(UUID pipelineId, UUID environmentId) {
        var pipeline = pipelineRepository.findById(pipelineId).orElse(null);
        var environment = environmentRepository.findById(environmentId).orElse(null);
        if (pipeline == null || environment == null) {
            return null;
        }
        var planId = resolvePlanId(pipeline, environment);
        return planId == null ? null : reviewPlanLookupService.findById(planId).orElse(null);
    }

    private static UUID resolvePlanId(DeploymentPipelineEntity pipeline,
                                      DeploymentEnvironmentEntity environment) {
        return environment.getReviewPlanId() != null
                ? environment.getReviewPlanId() : pipeline.getReviewPlanId();
    }

    private static boolean isApproverAtStage(ReviewPlanSnapshot plan, int stage,
                                             ReviewerContext context) {
        return plan.approvers().stream()
                .filter(rule -> rule.stage() == stage)
                .anyMatch(rule -> (rule.userId() != null && rule.userId().equals(context.userId()))
                        || (rule.role() != null && rule.role().equalsIgnoreCase(context.roleName())));
    }

    private static boolean hasPermission(ReviewerContext context, Permission permission) {
        return context.permissions() != null && context.permissions().contains(permission);
    }

    private DeploymentReviewDecisionEntity record(UUID deploymentRequestId, UUID reviewerId,
                                                  DecisionType decision, String comment) {
        var entity = new DeploymentReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setDeploymentRequestId(deploymentRequestId);
        entity.setReviewerId(reviewerId);
        entity.setDecision(decision);
        entity.setComment(comment);
        entity.setStage(STAGE);
        return decisionRepository.save(entity);
    }

    private DeploymentRequestEntity require(UUID id, UUID organizationId) {
        return requestRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new DeploymentRequestNotFoundException(id));
    }

    private PendingDeploymentReview toPending(DeploymentRequestEntity e) {
        var pipelineName = pipelineRepository.findById(e.getPipelineId())
                .map(DeploymentPipelineEntity::getName).orElse(null);
        var environmentName = environmentRepository.findById(e.getEnvironmentId())
                .map(DeploymentEnvironmentEntity::getName).orElse(null);
        var summary = e.getAiAnalysisId() != null
                ? aiAnalysisLookupService.findById(e.getAiAnalysisId()).orElse(null) : null;
        return new PendingDeploymentReview(e.getId(), e.getPipelineId(), pipelineName,
                e.getEnvironmentId(), environmentName, e.getSubmittedBy(), e.getVersion(),
                e.getCommitSha(), e.getRunUrl(), e.getJustification(), e.getAiAnalysisId(),
                summary != null ? summary.riskLevel() : null,
                summary != null ? summary.riskScore() : null,
                summary != null ? summary.summary() : null, STAGE, e.getRequiredApprovals(),
                e.getScheduledFor(), e.getCreatedAt());
    }
}
