package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupReviewPlanResolverTest {

    @Mock
    private ReviewPlanLookupService reviewPlanLookupService;
    @Mock
    private ReviewerEligibilityService reviewerEligibilityService;
    @Mock
    private ApiConnectorAdminService apiConnectorAdminService;
    @InjectMocks
    private GroupReviewPlanResolver resolver;

    private RequestGroupEntity group;

    @BeforeEach
    void setUp() {
        group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(UUID.randomUUID());
        lenient().when(reviewerEligibilityService.findEligibleReviewerIds(any()))
                .thenReturn(Optional.empty());
    }

    private RequestGroupItemEntity queryItem(UUID datasourceId) {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        return item;
    }

    private ReviewPlanSnapshot plan(int minApprovals, ApproverRule... approvers) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), group.getOrganizationId(), false, true,
                minApprovals, false, 1, List.of(approvers), List.of());
    }

    @Test
    void requiredApprovalsIsMaxAcrossMemberPlansAndApproversAreUnioned() {
        var ds1 = UUID.randomUUID();
        var ds2 = UUID.randomUUID();
        var approverA = UUID.randomUUID();
        var approverB = UUID.randomUUID();
        when(reviewPlanLookupService.findForDatasource(ds1))
                .thenReturn(Optional.of(plan(1, new ApproverRule(approverA, null, 1))));
        when(reviewPlanLookupService.findForDatasource(ds2))
                .thenReturn(Optional.of(plan(2, new ApproverRule(approverB, "REVIEWER", 1))));

        var resolution = resolver.resolve(group, List.of(queryItem(ds1), queryItem(ds2)));

        assertThat(resolution.requiresHumanApproval()).isTrue();
        assertThat(resolution.requiredApprovals()).isEqualTo(2);
        assertThat(resolution.eligibleUserIds()).containsExactlyInAnyOrder(approverA, approverB);
        assertThat(resolution.eligibleRoleNames()).contains("REVIEWER");
    }

    @Test
    void noPlansMeansNoHumanApprovalRequired() {
        var ds = UUID.randomUUID();
        when(reviewPlanLookupService.findForDatasource(ds)).thenReturn(Optional.empty());

        var resolution = resolver.resolve(group, List.of(queryItem(ds)));

        assertThat(resolution.requiresHumanApproval()).isFalse();
        assertThat(resolution.requiredApprovals()).isZero();
        assertThat(resolution.eligibleRoleNames()).isEmpty();
    }

    @Test
    void apiMemberResolvesConnectorReviewPlan() {
        var connectorId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var apiItem = new RequestGroupItemEntity();
        apiItem.setId(UUID.randomUUID());
        apiItem.setTargetKind(RequestGroupTargetKind.API_CALL);
        apiItem.setApiConnectorId(connectorId);
        var connectorView = org.mockito.Mockito.mock(
                com.bablsoft.accessflow.apigov.api.ApiConnectorView.class);
        when(connectorView.reviewPlanId()).thenReturn(planId);
        when(apiConnectorAdminService.getForAdmin(connectorId, group.getOrganizationId()))
                .thenReturn(connectorView);
        when(reviewPlanLookupService.findById(planId))
                .thenReturn(Optional.of(plan(1, new ApproverRule(null, "REVIEWER", 1))));

        var resolution = resolver.resolve(group, List.of(apiItem));

        assertThat(resolution.requiresHumanApproval()).isTrue();
        assertThat(resolution.eligibleRoleNames()).contains("REVIEWER");
    }

    @Test
    void propagatesWhenConnectorLookupFails() {
        var connectorId = UUID.randomUUID();
        var apiItem = new RequestGroupItemEntity();
        apiItem.setId(UUID.randomUUID());
        apiItem.setTargetKind(RequestGroupTargetKind.API_CALL);
        apiItem.setApiConnectorId(connectorId);
        when(apiConnectorAdminService.getForAdmin(connectorId, group.getOrganizationId()))
                .thenThrow(new RuntimeException("gone"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> resolver.resolve(group, List.of(apiItem)))
                .isInstanceOf(RuntimeException.class);
    }
}
