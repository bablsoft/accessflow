package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.ReviewPlanSpec;
import com.bablsoft.accessflow.core.api.CreateReviewPlanCommand;
import com.bablsoft.accessflow.core.api.ReviewPlanAdminService;
import com.bablsoft.accessflow.core.api.ReviewPlanView;
import com.bablsoft.accessflow.core.api.UpdateReviewPlanCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewPlanReconcilerTest {

    @Mock ReviewPlanAdminService reviewPlanAdminService;
    @Mock AdminUserReconciler adminUserReconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    private ReviewPlanReconciler reconciler() {
        return new ReviewPlanReconciler(reviewPlanAdminService, adminUserReconciler);
    }

    @Test
    void createsPlanWhenNameNotFound() {
        var planId = UUID.randomUUID();
        var channelId = UUID.randomUUID();
        var approverId = UUID.randomUUID();
        when(reviewPlanAdminService.list(ORG_ID)).thenReturn(List.of());
        when(adminUserReconciler.lookupId(ORG_ID, "admin@acme.com")).thenReturn(approverId);
        when(reviewPlanAdminService.create(any(CreateReviewPlanCommand.class)))
                .thenAnswer(inv -> view(planId, "standard"));

        var spec = new ReviewPlanSpec("standard", "desc", true, true, 1, 24, false,
                List.of("ops"), List.of("admin@acme.com"));

        var result = reconciler().reconcile(ORG_ID, List.of(spec), Map.of("ops", channelId));

        assertThat(result).containsEntry("standard", planId);

        var captor = ArgumentCaptor.forClass(CreateReviewPlanCommand.class);
        verify(reviewPlanAdminService).create(captor.capture());
        assertThat(captor.getValue().notifyChannels()).containsExactly(channelId.toString());
        assertThat(captor.getValue().approvers()).hasSize(1);
        assertThat(captor.getValue().approvers().get(0).userId()).isEqualTo(approverId);
    }

    @Test
    void updatesPlanWhenNameMatches() {
        var existingId = UUID.randomUUID();
        when(reviewPlanAdminService.list(ORG_ID)).thenReturn(List.of(view(existingId, "standard")));
        when(reviewPlanAdminService.update(eq(existingId), eq(ORG_ID),
                any(UpdateReviewPlanCommand.class)))
                .thenReturn(view(existingId, "standard"));

        var spec = new ReviewPlanSpec("standard", null, null, null, null, null, null,
                List.of(), List.of());

        var result = reconciler().reconcile(ORG_ID, List.of(spec), Map.of());

        assertThat(result).containsEntry("standard", existingId);
        verify(reviewPlanAdminService, never()).create(any());
    }

    @Test
    void throwsWhenNotifyChannelMissing() {
        var spec = new ReviewPlanSpec("standard", null, null, null, null, null, null,
                List.of("missing-channel"), List.of());

        assertThatThrownBy(() -> reconciler().reconcile(ORG_ID, List.of(spec), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-channel");
    }

    @Test
    void throwsWhenApproverEmailMissing() {
        when(adminUserReconciler.lookupId(ORG_ID, "missing@acme.com"))
                .thenThrow(new IllegalStateException("missing"));
        var spec = new ReviewPlanSpec("standard", null, null, null, null, null, null,
                List.of(), List.of("missing@acme.com"));

        assertThatThrownBy(() -> reconciler().reconcile(ORG_ID, List.of(spec), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void throwsWhenNameMissing() {
        var spec = new ReviewPlanSpec(" ", null, null, null, null, null, null,
                List.of(), List.of());
        assertThatThrownBy(() -> reconciler().reconcile(ORG_ID, List.of(spec), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    private ReviewPlanView view(UUID id, String name) {
        return new ReviewPlanView(id, ORG_ID, name, "", true, true, 1, 24, false,
                List.of(), List.of(), Instant.now());
    }
}
