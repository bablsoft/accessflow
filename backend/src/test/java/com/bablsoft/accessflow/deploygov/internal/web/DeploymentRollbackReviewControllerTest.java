package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentRollbackReviewControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();

    private DeploymentRollbackReviewService reviewService;
    private DeploymentRollbackReviewController controller;

    @BeforeEach
    void setUp() {
        reviewService = mock(DeploymentRollbackReviewService.class);
        controller = new DeploymentRollbackReviewController(reviewService);
    }

    @Test
    void listDelegatesWithTheStatusFilter() {
        when(reviewService.list(eq(orgId), eq(DeploymentRollbackReviewStatus.PENDING_REVIEW),
                any())).thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));

        var page = controller.list(DeploymentRollbackReviewStatus.PENDING_REVIEW, auth(),
                Pageable.ofSize(20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().id()).isEqualTo(reviewId);
    }

    @Test
    void getDelegates() {
        when(reviewService.get(reviewId, orgId)).thenReturn(view());

        assertThat(controller.get(reviewId, auth()).id()).isEqualTo(reviewId);
    }

    @Test
    void acknowledgeDelegatesTheComment() {
        when(reviewService.acknowledge(reviewId, orgId, userId, "ack")).thenReturn(view());

        controller.acknowledge(reviewId, new AcknowledgeDeploymentRollbackRequest("ack"), auth());

        verify(reviewService).acknowledge(reviewId, orgId, userId, "ack");
    }

    @Test
    void acknowledgeToleratesAMissingBody() {
        when(reviewService.acknowledge(reviewId, orgId, userId, null)).thenReturn(view());

        controller.acknowledge(reviewId, null, auth());

        verify(reviewService).acknowledge(reviewId, orgId, userId, null);
    }

    private Authentication auth() {
        var claims = new JwtClaims(userId, "reviewer@example.com", UserRoleType.REVIEWER,
                UUID.randomUUID(), "REVIEWER", Set.<Permission>of(Permission.DEPLOYMENT_REVIEW),
                orgId, false);
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(claims);
        return authentication;
    }

    private DeploymentRollbackReviewView view() {
        return new DeploymentRollbackReviewView(reviewId, UUID.randomUUID(), orgId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "detail",
                DeploymentRollbackReviewStatus.PENDING_REVIEW, null, null, null,
                Instant.parse("2026-08-24T12:00:00Z"));
    }
}
