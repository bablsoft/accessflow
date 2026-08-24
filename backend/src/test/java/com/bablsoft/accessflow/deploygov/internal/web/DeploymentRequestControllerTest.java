package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestSubmissionResult;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentRequestControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    private DeploymentRequestService requestService;
    private DeploymentRequestController controller;

    @BeforeEach
    void setUp() {
        requestService = mock(DeploymentRequestService.class);
        controller = new DeploymentRequestController(requestService);
    }

    @Test
    void submitAnswers202OnCreateAndPassesTheCallerContext() {
        when(requestService.submit(any()))
                .thenReturn(new DeploymentRequestSubmissionResult(view(), false));

        var response = controller.submit(submitBody(), auth(false),
                new RequestAuditContext("10.0.0.1", "curl/8"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().version()).isEqualTo("2.4.1");
        // The view rides back on the submission result - no second read, so a replay by a user who
        // could not read the original request still gets a body.
        verify(requestService, never()).get(any(), any(), any(), any());
        var captor = ArgumentCaptor.forClass(SubmitDeploymentRequestCommand.class);
        verify(requestService).submit(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().submitterUserId()).isEqualTo(userId);
        assertThat(captor.getValue().submittedIp()).isEqualTo("10.0.0.1");
        assertThat(captor.getValue().admin()).isFalse();
        assertThat(captor.getValue().environment()).isEqualTo("production");
    }

    @Test
    void submitAnswers200OnAnIdempotentReplay() {
        when(requestService.submit(any()))
                .thenReturn(new DeploymentRequestSubmissionResult(view(), true));

        var response = controller.submit(submitBody(), auth(false),
                new RequestAuditContext(null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void submitMarksAQueryAdminCallerAsAdmin() {
        when(requestService.submit(any()))
                .thenReturn(new DeploymentRequestSubmissionResult(view(), false));

        controller.submit(submitBody(), auth(true), new RequestAuditContext(null, null));

        var captor = ArgumentCaptor.forClass(SubmitDeploymentRequestCommand.class);
        verify(requestService).submit(captor.capture());
        assertThat(captor.getValue().admin()).isTrue();
    }

    @Test
    void listHardScopesANonAdminToTheirOwnSubmissions() {
        when(requestService.list(any(), any()))
                .thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));

        var page = controller.list(QueryStatus.APPROVED, pipelineId, "production", "2.4.1",
                UUID.randomUUID(), null, null, auth(false), Pageable.ofSize(20));

        assertThat(page.content()).hasSize(1);
        var captor = ArgumentCaptor.forClass(DeploymentRequestListFilter.class);
        verify(requestService).list(captor.capture(), any());
        assertThat(captor.getValue().submittedByUserId()).isEqualTo(userId);
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().pipelineId()).isEqualTo(pipelineId);
        assertThat(captor.getValue().environment()).isEqualTo("production");
        assertThat(captor.getValue().version()).isEqualTo("2.4.1");
        assertThat(captor.getValue().status()).isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void listHonoursSubmittedByForAnAdmin() {
        var other = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");
        when(requestService.list(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        controller.list(null, null, null, null, other, from, to, auth(true), Pageable.ofSize(20));

        var captor = ArgumentCaptor.forClass(DeploymentRequestListFilter.class);
        verify(requestService).list(captor.capture(), any());
        assertThat(captor.getValue().submittedByUserId()).isEqualTo(other);
        assertThat(captor.getValue().from()).isEqualTo(from);
        assertThat(captor.getValue().to()).isEqualTo(to);
    }

    @Test
    void listLeavesAnAdminUnscopedWhenNoSubmitterIsGiven() {
        when(requestService.list(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        controller.list(null, null, null, null, null, null, null, auth(true), Pageable.ofSize(20));

        var captor = ArgumentCaptor.forClass(DeploymentRequestListFilter.class);
        verify(requestService).list(captor.capture(), any());
        assertThat(captor.getValue().submittedByUserId()).isNull();
    }

    @Test
    void getMapsTheDetailView() {
        when(requestService.get(requestId, orgId, userId, Set.of())).thenReturn(view());

        var response = controller.get(requestId, auth(false));

        assertThat(response.id()).isEqualTo(requestId);
        assertThat(response.pipelineName()).isEqualTo("payments-api");
        assertThat(response.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(response.decisions()).isEmpty();
    }

    @Test
    void cancelDelegatesToTheService() {
        controller.cancel(requestId, auth(false));

        verify(requestService).cancel(requestId, orgId, userId);
    }

    private SubmitDeploymentRequestRequest submitBody() {
        return new SubmitDeploymentRequestRequest(pipelineId, "production", "2.4.1", "abc123",
                null, null, "run-1", Map.of("changelog", "fix"), "ship it", null);
    }

    private DeploymentRequestView view() {
        return new DeploymentRequestView(requestId, pipelineId, "payments-api",
                PipelineProvider.GITHUB_ACTIONS, UUID.randomUUID(), "production", userId,
                "ci@example.com", "2.4.1", "abc123", null, null, "run-1", Map.of(),
                QueryStatus.PENDING_REVIEW, SubmissionReason.USER_SUBMITTED, "ship it",
                UUID.randomUUID(), RiskLevel.HIGH, 80, "schema migration", 2, null, null, null,
                null, Instant.parse("2026-08-21T17:30:00Z"), List.of());
    }

    private Authentication auth(boolean admin) {
        var claims = new JwtClaims(userId, "ci@example.com", UserRoleType.ANALYST, UUID.randomUUID(),
                "ANALYST", admin ? Set.of(Permission.QUERY_ADMIN) : Set.of(), orgId, false);
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(claims);
        return authentication;
    }
}
