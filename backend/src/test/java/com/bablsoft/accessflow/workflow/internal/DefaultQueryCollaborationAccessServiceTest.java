package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultQueryCollaborationAccessServiceTest {

    private final QueryRequestLookupService queryLookup = mock(QueryRequestLookupService.class);
    private final ReviewPlanLookupService planLookup = mock(ReviewPlanLookupService.class);
    private final ReviewerEligibilityService eligibility = mock(ReviewerEligibilityService.class);
    private final UserQueryService userQuery = mock(UserQueryService.class);

    private final DefaultQueryCollaborationAccessService service =
            new DefaultQueryCollaborationAccessService(queryLookup, planLookup, eligibility,
                    userQuery);

    private final UUID queryId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();

    @Test
    void submitterCanCollaborateWhilePendingReviewAndPendingAi() {
        stubQuery(QueryStatus.PENDING_REVIEW);
        assertThat(service.canCollaborate(queryId, submitterId, orgId, UserRoleType.ANALYST))
                .isTrue();

        stubQuery(QueryStatus.PENDING_AI);
        assertThat(service.canCollaborate(queryId, submitterId, orgId, UserRoleType.ANALYST))
                .isTrue();
    }

    @Test
    void submitterCannotCollaborateOnceApproved() {
        stubQuery(QueryStatus.APPROVED);
        assertThat(service.canCollaborate(queryId, submitterId, orgId, UserRoleType.ANALYST))
                .isFalse();
    }

    @Test
    void adminCanCollaborateOnInReviewQueryWithoutBeingAnApprover() {
        stubQuery(QueryStatus.PENDING_REVIEW);
        when(planLookup.findForDatasource(datasourceId)).thenReturn(Optional.empty());

        assertThat(service.canCollaborate(queryId, UUID.randomUUID(), orgId, UserRoleType.ADMIN))
                .isTrue();
    }

    @Test
    void assignedReviewerCanCollaborate() {
        var reviewerId = UUID.randomUUID();
        stubQuery(QueryStatus.PENDING_REVIEW);
        when(planLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(new ApproverRule(null, UserRoleType.REVIEWER, 1))));
        when(eligibility.findEligibleReviewerIds(datasourceId)).thenReturn(Optional.empty());

        assertThat(service.canCollaborate(queryId, reviewerId, orgId, UserRoleType.REVIEWER))
                .isTrue();
    }

    @Test
    void reviewerNotInPlanCannotCollaborate() {
        var reviewerId = UUID.randomUUID();
        stubQuery(QueryStatus.PENDING_REVIEW);
        when(planLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(new ApproverRule(UUID.randomUUID(), null, 1))));

        assertThat(service.canCollaborate(queryId, reviewerId, orgId, UserRoleType.REVIEWER))
                .isFalse();
    }

    @Test
    void outsiderAnalystCannotCollaborate() {
        stubQuery(QueryStatus.PENDING_REVIEW);
        assertThat(service.canCollaborate(queryId, UUID.randomUUID(), orgId, UserRoleType.ANALYST))
                .isFalse();
    }

    @Test
    void unknownQueryCannotCollaborate() {
        when(queryLookup.findById(queryId)).thenReturn(Optional.empty());
        assertThat(service.canCollaborate(queryId, submitterId, orgId, UserRoleType.ADMIN))
                .isFalse();
    }

    @Test
    void queryInDifferentOrganizationCannotCollaborate() {
        stubQuery(QueryStatus.PENDING_REVIEW);
        assertThat(service.canCollaborate(queryId, submitterId, UUID.randomUUID(),
                UserRoleType.ADMIN)).isFalse();
    }

    @Test
    void resolveParticipantReturnsIdentityWhenAllowed() {
        stubQuery(QueryStatus.PENDING_REVIEW);
        when(userQuery.findById(submitterId)).thenReturn(Optional.of(user(submitterId, "Ann")));

        var identity = service.resolveParticipant(queryId, submitterId, orgId,
                UserRoleType.ANALYST);

        assertThat(identity).isPresent();
        assertThat(identity.get().displayName()).isEqualTo("Ann");
    }

    @Test
    void resolveParticipantEmptyWhenDenied() {
        stubQuery(QueryStatus.APPROVED);
        assertThat(service.resolveParticipant(queryId, submitterId, orgId, UserRoleType.ANALYST))
                .isEmpty();
    }

    @Test
    void collaboratorIdsReturnsSubmitterPlusReviewers() {
        var reviewerId = UUID.randomUUID();
        stubQuery(QueryStatus.PENDING_REVIEW);
        when(planLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(new ApproverRule(null, UserRoleType.REVIEWER, 1))));
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.REVIEWER))
                .thenReturn(List.of(user(reviewerId, "Rev")));
        when(eligibility.findEligibleReviewerIds(any())).thenReturn(Optional.empty());

        assertThat(service.collaboratorIds(queryId, orgId))
                .containsExactlyInAnyOrder(submitterId, reviewerId);
    }

    @Test
    void collaboratorIdsEmptyForUnknownQuery() {
        when(queryLookup.findById(queryId)).thenReturn(Optional.empty());
        assertThat(service.collaboratorIds(queryId, orgId)).isEmpty();
    }

    private void stubQuery(QueryStatus status) {
        when(queryLookup.findById(queryId)).thenReturn(Optional.of(new QueryRequestSnapshot(
                queryId, datasourceId, orgId, submitterId, "SELECT 1", QueryType.SELECT, false,
                status, null)));
    }

    private ReviewPlanSnapshot plan(ApproverRule... rules) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), orgId, true, true, 1, false, 1,
                List.of(rules), List.of());
    }

    private UserView user(UUID id, String displayName) {
        return new UserView(id, displayName.toLowerCase() + "@example.com", displayName,
                UserRoleType.REVIEWER, orgId, true, null, null, null, null, false, Instant.now());
    }
}
