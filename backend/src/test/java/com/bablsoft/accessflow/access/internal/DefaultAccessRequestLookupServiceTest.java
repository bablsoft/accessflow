package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccessRequestLookupServiceTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessRequestViewMapper viewMapper;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock ReviewerEligibilityService reviewerEligibilityService;
    @Mock UserQueryService userQueryService;

    private DefaultAccessRequestLookupService service() {
        return new DefaultAccessRequestLookupService(requestRepository, viewMapper,
                reviewPlanLookupService, reviewerEligibilityService, userQueryService);
    }

    private final UUID requestId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    private AccessGrantRequestEntity entity() {
        var e = new AccessGrantRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(organizationId);
        e.setRequesterId(requesterId);
        e.setDatasourceId(datasourceId);
        e.setStatus(AccessGrantStatus.PENDING);
        e.setRequestedDuration("PT4H");
        return e;
    }

    private UserView user(UUID id, boolean active) {
        return new UserView(id, id + "@x.io", "U", UserRoleType.REVIEWER, organizationId, active,
                AuthProviderType.LOCAL, null, null, "en", false, Instant.now());
    }

    @Test
    void findByIdReturnsEmptyWhenAbsent() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());
        assertThat(service().findById(requestId)).isEmpty();
    }

    @Test
    void findReviewerRecipientsEmptyWhenRequestUnknown() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());
        assertThat(service().findReviewerRecipients(requestId)).isEmpty();
    }

    @Test
    void findReviewerRecipientsEmptyWhenNoPlanAndNoAdmins() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity()));
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        when(userQueryService.findByOrganizationAndRole(organizationId, UserRoleType.ADMIN))
                .thenReturn(List.of());
        assertThat(service().findReviewerRecipients(requestId)).isEmpty();
    }

    @Test
    void findReviewerRecipientsFallsBackToActiveAdminsWhenNoPlan() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity()));
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        var adminA = UUID.randomUUID();
        var inactiveAdmin = UUID.randomUUID();
        when(userQueryService.findByOrganizationAndRole(organizationId, UserRoleType.ADMIN))
                .thenReturn(List.of(user(adminA, true), user(inactiveAdmin, false),
                        user(requesterId, true)));

        var recipients = service().findReviewerRecipients(requestId);

        // Active admins only, and never the requester (even when the requester is an admin).
        assertThat(recipients).containsExactly(adminA);
    }

    @Test
    void findReviewerRecipientsFallsBackToAdminsWhenDatasourceScopeFiltersEveryone() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity()));
        var planReviewer = UUID.randomUUID();
        var plan = new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, false, true, 1, false, 0,
                List.of(new ApproverRule(planReviewer, null, 0)), List.of());
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.of(plan));
        when(userQueryService.findById(planReviewer)).thenReturn(Optional.of(user(planReviewer, true)));
        // Scope set excludes the only plan approver → plan routes to nobody.
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(UUID.randomUUID())));
        var adminA = UUID.randomUUID();
        when(userQueryService.findByOrganizationAndRole(organizationId, UserRoleType.ADMIN))
                .thenReturn(List.of(user(adminA, true)));

        var recipients = service().findReviewerRecipients(requestId);

        assertThat(recipients).containsExactly(adminA);
    }

    @Test
    void findReviewerRecipientsResolvesRoleApproversExcludingRequester() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity()));
        var plan = new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, false, true, 1, false, 0,
                List.of(new ApproverRule(null, UserRoleType.REVIEWER, 0)), List.of());
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.of(plan));
        var reviewerA = UUID.randomUUID();
        when(userQueryService.findByOrganizationAndRole(organizationId, UserRoleType.REVIEWER))
                .thenReturn(List.of(user(reviewerA, true), user(requesterId, true)));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());

        var recipients = service().findReviewerRecipients(requestId);

        assertThat(recipients).containsExactly(reviewerA);
    }

    @Test
    void findReviewerRecipientsAppliesDatasourceScope() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity()));
        var scopedReviewer = UUID.randomUUID();
        var unscopedReviewer = UUID.randomUUID();
        var plan = new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, false, true, 1, false, 0,
                List.of(new ApproverRule(scopedReviewer, null, 0),
                        new ApproverRule(unscopedReviewer, null, 0)), List.of());
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.of(plan));
        when(userQueryService.findById(scopedReviewer)).thenReturn(Optional.of(user(scopedReviewer, true)));
        when(userQueryService.findById(unscopedReviewer)).thenReturn(Optional.of(user(unscopedReviewer, true)));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(scopedReviewer)));

        var recipients = service().findReviewerRecipients(requestId);

        assertThat(recipients).containsExactly(scopedReviewer);
    }
}
