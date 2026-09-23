package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeNotificationLookupService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionNotificationView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftNotificationView;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sibling of deploygov's {@code DefaultDeploymentNotificationLookupService} (#882). The reviewer set
 * mirrors requestgroups' {@code GroupReviewPlanResolver} for a single-datasource group — the target
 * datasource's plan approvers (a group has one approval stage, so every stage's rules count) plus
 * its reviewer assignments. When nobody is named, only {@code REVIEW_OVERRIDE} holders can act on
 * the group, so they — not the REVIEWER role — are the fallback.
 */
@Service
@RequiredArgsConstructor
class DefaultSchemaChangeNotificationLookupService implements SchemaChangeNotificationLookupService {

    private final SchemaChangeSetPromotionRepository promotionRepository;
    private final DeploymentPipelineLookupService pipelineLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final UserQueryService userQueryService;
    private final RolePermissionHolderLookupService permissionHolderLookupService;

    @Override
    @Transactional(readOnly = true)
    public Optional<SchemaChangePromotionNotificationView> findPromotion(UUID promotionId) {
        return promotionRepository.findById(promotionId).map(p -> {
            var changeSet = p.getChangeSet();
            var pipelineId = changeSet.getPipelineId();
            var pipelineName = pipelineLookupService.findPipeline(pipelineId, p.getOrganizationId())
                    .map(DeploymentPipelineView::name).orElse(null);
            var environmentName = pipelineLookupService.findEnvironment(pipelineId, p.getEnvironmentId())
                    .map(DeploymentEnvironmentView::name).orElse(null);
            return new SchemaChangePromotionNotificationView(p.getId(), p.getOrganizationId(),
                    changeSet.getId(), changeSet.getName(), pipelineId, pipelineName,
                    p.getEnvironmentId(), environmentName, p.getDatasourceId(), p.getPromotedBy(),
                    p.getStatus(), p.getErrorMessage());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findEligibleReviewerUserIds(UUID promotionId) {
        var promotion = promotionRepository.findById(promotionId).orElse(null);
        if (promotion == null) {
            return List.of();
        }
        var organizationId = promotion.getOrganizationId();
        var eligible = new LinkedHashSet<UUID>();
        reviewPlanLookupService.findForDatasource(promotion.getDatasourceId())
                .map(plan -> plan.approvers() == null ? List.<ApproverRule>of() : plan.approvers())
                .orElse(List.of())
                .forEach(rule -> {
                    if (rule.userId() != null) {
                        eligible.add(rule.userId());
                    } else if (rule.role() != null) {
                        userQueryService.findByOrganizationAndRoleName(organizationId, rule.role())
                                .stream().map(UserView::id).forEach(eligible::add);
                    }
                });
        reviewerEligibilityService.findEligibleReviewerIds(promotion.getDatasourceId())
                .ifPresent(eligible::addAll);
        if (eligible.isEmpty()) {
            eligible.addAll(permissionHolderLookupService.findUserIdsWithPermission(organizationId,
                    Permission.REVIEW_OVERRIDE));
        }
        eligible.remove(promotion.getPromotedBy());
        return List.copyOf(eligible);
    }

    @Override
    public Optional<SchemaDriftNotificationView> findDriftTarget(UUID organizationId, UUID pipelineId,
                                                                 UUID environmentId) {
        var pipeline = pipelineLookupService.findPipeline(pipelineId, organizationId).orElse(null);
        if (pipeline == null) {
            return Optional.empty();
        }
        return pipelineLookupService.findEnvironment(pipelineId, environmentId)
                .map(env -> new SchemaDriftNotificationView(organizationId, pipelineId, pipeline.name(),
                        environmentId, env.name()));
    }
}
