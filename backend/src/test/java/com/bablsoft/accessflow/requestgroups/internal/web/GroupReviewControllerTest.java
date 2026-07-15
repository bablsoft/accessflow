package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService.DecisionOutcome;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService.PendingGroupReview;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService.ReviewerContext;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupReviewControllerTest {

    private GroupReviewService service;
    private GroupReviewController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(GroupReviewService.class);
        controller = new GroupReviewController(service);
    }

    private Authentication auth() {
        var a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(JwtClaims.forSystemRole(userId, "r@acme.test", UserRoleType.REVIEWER, orgId));
        return a;
    }

    @Test
    void pendingMapsPage() {
        var pending = new PendingGroupReview(groupId, "bundle", UUID.randomUUID(), "Dana", 2,
                RiskLevel.HIGH, 80, 1, 1, Instant.now());
        when(service.listPending(any(), any()))
                .thenReturn(new PageResponse<>(List.of(pending), 0, 20, 1, 1));

        var resp = controller.pending(auth(), Pageable.ofSize(20));

        assertThat(resp.content()).hasSize(1);
        assertThat(resp.content().get(0).memberCount()).isEqualTo(2);
        var captor = ArgumentCaptor.forClass(ReviewerContext.class);
        verify(service).listPending(captor.capture(), any());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().roleName()).isEqualTo("REVIEWER");
    }

    @Test
    void approveDelegates() {
        when(service.approve(eq(groupId), any(), eq("ok")))
                .thenReturn(new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                        RequestGroupStatus.APPROVED, false));

        var resp = controller.approve(groupId, new GroupDecisionRequest("ok"), auth());

        assertThat(resp.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(resp.resultingStatus()).isEqualTo(RequestGroupStatus.APPROVED);
        assertThat(resp.idempotentReplay()).isFalse();
    }

    @Test
    void rejectDelegates() {
        when(service.reject(eq(groupId), any(), eq("no")))
                .thenReturn(new DecisionOutcome(UUID.randomUUID(), DecisionType.REJECTED,
                        RequestGroupStatus.REJECTED, false));

        var resp = controller.reject(groupId, new GroupDecisionRequest("no"), auth());

        assertThat(resp.decision()).isEqualTo(DecisionType.REJECTED);
        assertThat(resp.resultingStatus()).isEqualTo(RequestGroupStatus.REJECTED);
    }
}
