package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentNotificationLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentNotificationView;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sibling of apigov's {@code DefaultApiRequestNotificationLookupService} (#695). Recipient
 * resolution follows {@link DefaultDeploymentReviewService}'s plan rule — stage-1 approver rules
 * on the resolved plan (environment override wins over the pipeline's) restrict the reviewer set —
 * with a REVIEWER∪ADMIN system-role fallback when no rules exist. The fallback is deliberately
 * role-based, not permission-based (custom-role {@code DEPLOYMENT_REVIEW} holders are not
 * individually pinged), matching the apigov recipient convention; see the interface javadoc.
 */
@Service
@RequiredArgsConstructor
class DefaultDeploymentNotificationLookupService implements DeploymentNotificationLookupService {

    private static final int STAGE = 1;

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentReviewDecisionRepository decisionRepository;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final UserQueryService userQueryService;

    @Override
    @Transactional(readOnly = true)
    public Optional<DeploymentNotificationView> find(UUID deploymentRequestId) {
        return requestRepository.findById(deploymentRequestId).map(r -> {
            var pipelineName = pipelineRepository.findById(r.getPipelineId())
                    .map(DeploymentPipelineEntity::getName).orElse(null);
            var environmentName = environmentRepository.findById(r.getEnvironmentId())
                    .map(DeploymentEnvironmentEntity::getName).orElse(null);
            var summary = r.getAiAnalysisId() != null
                    ? aiAnalysisLookupService.findById(r.getAiAnalysisId()).orElse(null) : null;
            return new DeploymentNotificationView(r.getId(), r.getOrganizationId(),
                    r.getPipelineId(), pipelineName, environmentName, r.getVersion(),
                    r.getSubmittedBy(), r.getStatus(), r.getSubmissionReason(),
                    r.getJustification(),
                    summary != null ? summary.riskLevel() : null,
                    summary != null ? summary.riskScore() : null,
                    summary != null ? summary.summary() : null);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findEligibleReviewerUserIds(UUID deploymentRequestId) {
        var request = requestRepository.findById(deploymentRequestId).orElse(null);
        if (request == null) {
            return List.of();
        }
        var plan = resolvePlan(request);
        var eligible = new LinkedHashSet<UUID>();
        if (plan != null && plan.approvers() != null && !plan.approvers().isEmpty()) {
            for (ApproverRule rule : plan.approvers()) {
                if (rule.stage() != STAGE) {
                    continue;
                }
                if (rule.userId() != null) {
                    eligible.add(rule.userId());
                } else if (rule.role() != null) {
                    userQueryService
                            .findByOrganizationAndRoleName(request.getOrganizationId(), rule.role())
                            .stream()
                            .map(UserView::id)
                            .forEach(eligible::add);
                }
            }
        } else {
            for (var role : List.of(UserRoleType.REVIEWER, UserRoleType.ADMIN)) {
                userQueryService.findByOrganizationAndRole(request.getOrganizationId(), role)
                        .stream()
                        .map(UserView::id)
                        .forEach(eligible::add);
            }
        }
        eligible.remove(request.getSubmittedBy());
        return List.copyOf(eligible);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findApproverUserIds(UUID deploymentRequestId) {
        return decisionRepository
                .findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(deploymentRequestId)
                .stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVED)
                .map(d -> d.getReviewerId())
                .distinct()
                .toList();
    }

    /** The environment's plan override wins over the pipeline's — same rule as review/routing. */
    private ReviewPlanSnapshot resolvePlan(DeploymentRequestEntity request) {
        var pipeline = pipelineRepository.findById(request.getPipelineId()).orElse(null);
        var environment = environmentRepository.findById(request.getEnvironmentId()).orElse(null);
        if (pipeline == null || environment == null) {
            return null;
        }
        var planId = environment.getReviewPlanId() != null
                ? environment.getReviewPlanId() : pipeline.getReviewPlanId();
        return planId == null ? null : reviewPlanLookupService.findById(planId).orElse(null);
    }
}
