package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSchemaChangeNotificationLookupServiceTest {

    @Mock private SchemaChangeSetPromotionRepository promotionRepository;
    @Mock private DeploymentPipelineLookupService pipelineLookupService;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;
    @Mock private ReviewerEligibilityService reviewerEligibilityService;
    @Mock private UserQueryService userQueryService;
    @Mock private RolePermissionHolderLookupService permissionHolderLookupService;

    private DefaultSchemaChangeNotificationLookupService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID promoterId = UUID.randomUUID();
    private SchemaChangeSetPromotionEntity promotion;

    @BeforeEach
    void setUp() {
        service = new DefaultSchemaChangeNotificationLookupService(promotionRepository, pipelineLookupService,
                reviewPlanLookupService, reviewerEligibilityService, userQueryService, permissionHolderLookupService);
        var changeSet = new SchemaChangeSetEntity();
        changeSet.setId(UUID.randomUUID());
        changeSet.setOrganizationId(orgId);
        changeSet.setPipelineId(pipelineId);
        changeSet.setName("add-invoice-index");
        promotion = new SchemaChangeSetPromotionEntity();
        promotion.setId(UUID.randomUUID());
        promotion.setOrganizationId(orgId);
        promotion.setChangeSet(changeSet);
        promotion.setEnvironmentId(environmentId);
        promotion.setDatasourceId(datasourceId);
        promotion.setPromotedBy(promoterId);
        promotion.setStatus(SchemaChangePromotionStatus.FAILED);
        promotion.setErrorMessage("boom");
        lenient().when(promotionRepository.findById(promotion.getId())).thenReturn(Optional.of(promotion));
        lenient().when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId)).thenReturn(Optional.empty());
    }

    @Test
    void findPromotionResolvesTheNamesItRenders() {
        when(pipelineLookupService.findPipeline(pipelineId, orgId)).thenReturn(Optional.of(pipeline()));
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId)).thenReturn(Optional.of(environment()));

        var view = service.findPromotion(promotion.getId()).orElseThrow();

        assertThat(view.changeSetName()).isEqualTo("add-invoice-index");
        assertThat(view.pipelineName()).isEqualTo("billing");
        assertThat(view.environmentName()).isEqualTo("staging");
        assertThat(view.promotedBy()).isEqualTo(promoterId);
        assertThat(view.status()).isEqualTo(SchemaChangePromotionStatus.FAILED);
        assertThat(view.errorMessage()).isEqualTo("boom");
        assertThat(view.datasourceId()).isEqualTo(datasourceId);
    }

    @Test
    void findPromotionToleratesAVanishedPipelineOrEnvironment() {
        when(pipelineLookupService.findPipeline(pipelineId, orgId)).thenReturn(Optional.empty());
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId)).thenReturn(Optional.empty());

        var view = service.findPromotion(promotion.getId()).orElseThrow();

        assertThat(view.pipelineName()).isNull();
        assertThat(view.environmentName()).isNull();
    }

    @Test
    void findPromotionIsEmptyForAnUnknownId() {
        assertThat(service.findPromotion(UUID.randomUUID())).isEmpty();
        assertThat(service.findEligibleReviewerUserIds(UUID.randomUUID())).isEmpty();
    }

    @Test
    void reviewersAreEveryStagesApproversAndDatasourceReviewersWithoutThePromoter() {
        var directId = UUID.randomUUID();
        var laterStage = UUID.randomUUID();
        var roleHolder = UUID.randomUUID();
        var assigned = UUID.randomUUID();
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.of(plan(List.of(
                new ApproverRule(directId, null, 1),
                new ApproverRule(null, "DBA", 1),
                new ApproverRule(laterStage, null, 2),
                new ApproverRule(promoterId, null, 1)))));
        when(userQueryService.findByOrganizationAndRoleName(orgId, "DBA"))
                .thenReturn(List.of(user(roleHolder), user(promoterId)));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(assigned)));

        assertThat(service.findEligibleReviewerUserIds(promotion.getId()))
                .containsExactlyInAnyOrder(directId, roleHolder, laterStage, assigned);
        // A group has one approval stage: a stage-2 rule can approve it, so it must be told.
        verify(permissionHolderLookupService, never()).findUserIdsWithPermission(any(), any());
    }

    @Test
    void reviewersFallBackToReviewOverrideHoldersWhenNothingNamesAnyone() {
        var overrider = UUID.randomUUID();
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        when(permissionHolderLookupService.findUserIdsWithPermission(orgId, Permission.REVIEW_OVERRIDE))
                .thenReturn(List.of(overrider, promoterId));

        // Only REVIEW_OVERRIDE holders can act on a group no rule names anyone for.
        assertThat(service.findEligibleReviewerUserIds(promotion.getId())).containsExactly(overrider);
    }

    @Test
    void aPlanWithNullApproversAlsoFallsBack() {
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.of(plan(null)));
        when(permissionHolderLookupService.findUserIdsWithPermission(any(), any())).thenReturn(List.of());

        assertThat(service.findEligibleReviewerUserIds(promotion.getId())).isEmpty();
    }

    @Test
    void driftTargetNamesThePipelineAndEnvironment() {
        when(pipelineLookupService.findPipeline(pipelineId, orgId)).thenReturn(Optional.of(pipeline()));
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId)).thenReturn(Optional.of(environment()));

        var view = service.findDriftTarget(orgId, pipelineId, environmentId).orElseThrow();

        assertThat(view.pipelineName()).isEqualTo("billing");
        assertThat(view.environmentName()).isEqualTo("staging");
        assertThat(view.organizationId()).isEqualTo(orgId);
    }

    @Test
    void driftTargetIsEmptyWhenThePipelineOrEnvironmentIsGone() {
        when(pipelineLookupService.findPipeline(pipelineId, orgId)).thenReturn(Optional.empty());
        assertThat(service.findDriftTarget(orgId, pipelineId, environmentId)).isEmpty();

        when(pipelineLookupService.findPipeline(pipelineId, orgId)).thenReturn(Optional.of(pipeline()));
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId)).thenReturn(Optional.empty());
        assertThat(service.findDriftTarget(orgId, pipelineId, environmentId)).isEmpty();
    }

    private DeploymentPipelineView pipeline() {
        return new DeploymentPipelineView(pipelineId, orgId, "billing", PipelineProvider.values()[0], null, null,
                null, false, null, true, Instant.now(), Instant.now());
    }

    private DeploymentEnvironmentView environment() {
        return new DeploymentEnvironmentView(environmentId, pipelineId, "staging", 1, true, null, null, false,
                Instant.now(), List.of(), datasourceId);
    }

    private ReviewPlanSnapshot plan(List<ApproverRule> approvers) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), orgId, true, true, 1, false, 1, approvers, List.of(),
                null, null);
    }

    private UserView user(UUID id) {
        return new UserView(id, id + "@example.com", "User", UserRoleType.REVIEWER, orgId, true,
                AuthProviderType.LOCAL, "h", null, null, false, Instant.now());
    }
}
