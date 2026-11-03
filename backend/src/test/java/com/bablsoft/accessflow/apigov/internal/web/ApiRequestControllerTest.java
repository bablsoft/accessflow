package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.SubmitApiRequestCommand;
import com.bablsoft.accessflow.serviceaccounts.api.OnBehalfOfPrincipalService;
import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.apigov.api.ApiRequestService;
import com.bablsoft.accessflow.apigov.api.ApiRequestSubmissionResult;
import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffCommand;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffEntry;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffFormat;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffKind;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffService;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffView;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiRequestControllerTest {

    private ApiRequestService requestService;
    private ApiAssistService assistService;
    private ApiRequestController controller;
    private OnBehalfOfPrincipalService onBehalfOfPrincipalService;
    private com.bablsoft.accessflow.apigov.api.ApiRequestReplayService replayService;
    private ApiResponseDiffService diffService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("1.2.3.4", "ua");

    @BeforeEach
    void setUp() {
        requestService = mock(ApiRequestService.class);
        assistService = mock(ApiAssistService.class);
        onBehalfOfPrincipalService = mock(OnBehalfOfPrincipalService.class);
        replayService = mock(com.bablsoft.accessflow.apigov.api.ApiRequestReplayService.class);
        diffService = mock(ApiResponseDiffService.class);
        controller = new ApiRequestController(requestService, assistService, onBehalfOfPrincipalService,
                replayService, diffService);
    }

    private Authentication auth(UserRoleType role) {
        var a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(JwtClaims.forSystemRole(userId, "u@acme.test", role, orgId));
        return a;
    }

    private ApiRequestView view() {
        return new ApiRequestView(requestId, connectorId, "Stripe", userId, "u@acme.test", null, "POST",
                "/charges", true, QueryStatus.PENDING_AI, SubmissionReason.USER_SUBMITTED, "need", null, null,
                null, null, com.bablsoft.accessflow.apigov.api.ApiBodyType.RAW, java.util.Map.of(), null, null, null, null, null,
                null, false, null, false, null, null, Instant.now(), List.of());
    }

    @Test
    void submitReturnsDetail() {
        when(requestService.submit(any())).thenReturn(new ApiRequestSubmissionResult(requestId, QueryStatus.PENDING_AI));
        when(requestService.get(eq(requestId), eq(orgId), eq(userId),
                eq(SystemRolePermissions.of(UserRoleType.ANALYST))))
                .thenReturn(view());

        var response = controller.submit(new SubmitApiRequestRequest(connectorId, null, "POST", "/charges",
                null, null, null, null, "{}", null, null, java.util.Map.of(), "need", null, null),
                auth(UserRoleType.ANALYST), auditContext);

        assertThat(response.id()).isEqualTo(requestId);
        var captor = ArgumentCaptor.forClass(SubmitApiRequestCommand.class);
        verify(requestService).submit(captor.capture());
        assertThat(captor.getValue().onBehalfOfUserId()).isNull();
    }

    /** #874: the principal the filter parked is stamped on the command. */
    @Test
    void submitStampsTheOnBehalfOfPrincipal() {
        var alice = UUID.randomUUID();
        when(onBehalfOfPrincipalService.current()).thenReturn(Optional.of(alice));
        when(requestService.submit(any())).thenReturn(new ApiRequestSubmissionResult(requestId, QueryStatus.PENDING_AI));
        when(requestService.get(eq(requestId), eq(orgId), eq(userId),
                eq(SystemRolePermissions.of(UserRoleType.ANALYST))))
                .thenReturn(view());

        controller.submit(new SubmitApiRequestRequest(connectorId, null, "POST", "/charges",
                null, null, null, null, "{}", null, null, java.util.Map.of(), "need", null, null),
                auth(UserRoleType.ANALYST), auditContext);

        var captor = ArgumentCaptor.forClass(SubmitApiRequestCommand.class);
        verify(requestService).submit(captor.capture());
        assertThat(captor.getValue().onBehalfOfUserId()).isEqualTo(alice);
    }

    @Test
    void getForwardsCallerRoleSoReviewersCanView() {
        when(requestService.get(eq(requestId), eq(orgId), eq(userId),
                eq(SystemRolePermissions.of(UserRoleType.REVIEWER))))
                .thenReturn(view());

        var response = controller.get(requestId, auth(UserRoleType.REVIEWER));

        assertThat(response.id()).isEqualTo(requestId);
        verify(requestService).get(requestId, orgId, userId, SystemRolePermissions.of(UserRoleType.REVIEWER));
    }

    @Test
    void listForAdminPassesNullSubmitterAndForwardsFilters() {
        when(requestService.list(any(), any())).thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));
        var from = Instant.parse("2026-01-01T00:00:00Z");

        var page = controller.list(auth(UserRoleType.ADMIN), Pageable.ofSize(20), QueryStatus.APPROVED,
                connectorId, "get", null, null, null, from, null);

        assertThat(page.content()).hasSize(1);
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.apigov.api.ApiRequestListFilter.class);
        verify(requestService).list(captor.capture(), any());
        var filter = captor.getValue();
        assertThat(filter.organizationId()).isEqualTo(orgId);
        assertThat(filter.submittedByUserId()).isNull();
        assertThat(filter.connectorId()).isEqualTo(connectorId);
        assertThat(filter.status()).isEqualTo(QueryStatus.APPROVED);
        assertThat(filter.verb()).isEqualTo("GET");
        assertThat(filter.from()).isEqualTo(from);
    }

    @Test
    void listForNonAdminScopesToOwnSubmissions() {
        when(requestService.list(any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        controller.list(auth(UserRoleType.ANALYST), Pageable.ofSize(20), null, null, null, null, null, null,
                null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.apigov.api.ApiRequestListFilter.class);
        verify(requestService).list(captor.capture(), any());
        assertThat(captor.getValue().submittedByUserId()).isEqualTo(userId);
        assertThat(captor.getValue().verb()).isNull();
    }

    @Test
    void cancelDelegates() {
        controller.cancel(requestId, auth(UserRoleType.ANALYST));
        verify(requestService).cancel(requestId, orgId, userId);
    }

    @Test
    void executeReturnsView() {
        when(requestService.execute(requestId, orgId, userId, false)).thenReturn(view());
        assertThat(controller.execute(requestId, auth(UserRoleType.ANALYST)).id()).isEqualTo(requestId);
    }

    @Test
    void downloadResponseSetsContentTypeAndAttachment() {
        when(requestService.downloadResponse(requestId, orgId, userId,
                SystemRolePermissions.of(UserRoleType.ANALYST)))
                .thenReturn(new com.bablsoft.accessflow.apigov.api.ApiResponsePayload(
                        "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/json", "api-response-x.json"));

        var response = controller.downloadResponse(requestId, auth(UserRoleType.ANALYST));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("api-response-x.json");
    }

    @Test
    void analyzeDelegates() {
        when(assistService.analyze(eq(connectorId), eq(orgId), eq(userId), eq(false), any()))
                .thenReturn(new ApiAssistService.ApiAiPreview(10, com.bablsoft.accessflow.core.api.RiskLevel.LOW, "ok", List.of()));
        var r = controller.analyze(new AnalyzeApiCallRequest(connectorId, null, "GET", "/x", null, "en"),
                auth(UserRoleType.ANALYST));
        assertThat(r.riskScore()).isEqualTo(10);
    }

    @Test
    void generateDelegates() {
        when(assistService.generate(eq(connectorId), eq(orgId), eq(userId), eq(false), eq("p"), eq("en")))
                .thenReturn(new ApiAssistService.GeneratedApiCallView("draft"));
        var r = controller.generate(new GenerateApiCallRequest(connectorId, "p", "en"), auth(UserRoleType.ANALYST));
        assertThat(r.draft()).isEqualTo("draft");
    }

    @Test
    void replayReturnsNewRequestWithDroppedCountAndForwardsCaller() {
        var sourceId = UUID.randomUUID();
        var alice = UUID.randomUUID();
        when(onBehalfOfPrincipalService.current()).thenReturn(Optional.of(alice));
        when(replayService.replay(any())).thenReturn(
                new com.bablsoft.accessflow.apigov.api.ApiRequestReplayService.ReplayResult(
                        requestId, QueryStatus.PENDING_AI, 3));
        var permissions = SystemRolePermissions.of(UserRoleType.ANALYST);
        when(requestService.get(requestId, orgId, userId, permissions)).thenReturn(view());

        var response = controller.replay(sourceId, auth(UserRoleType.ANALYST), auditContext);

        assertThat(response.request().id()).isEqualTo(requestId);
        assertThat(response.droppedVariableOverrideCount()).isEqualTo(3);
        var captor = ArgumentCaptor.forClass(
                com.bablsoft.accessflow.apigov.api.ApiRequestReplayService.ReplayCommand.class);
        verify(replayService).replay(captor.capture());
        var command = captor.getValue();
        assertThat(command.originalApiRequestId()).isEqualTo(sourceId);
        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.callerUserId()).isEqualTo(userId);
        assertThat(command.admin()).isFalse();
        assertThat(command.ipAddress()).isEqualTo("1.2.3.4");
        assertThat(command.userAgent()).isEqualTo("ua");
        assertThat(command.onBehalfOfUserId()).isEqualTo(alice);
    }

    private ApiResponseDiffView diffView(UUID previousId) {
        return new ApiResponseDiffView(requestId, previousId, ApiResponseDiffFormat.JSON, 200, 200, null, 4L,
                0, 0, 1, false, false,
                List.of(new ApiResponseDiffEntry("total", ApiResponseDiffKind.CHANGED, "1", "2")));
    }

    @Test
    void diffDefaultsToPreviousAndForwardsCaller() {
        var previousId = UUID.randomUUID();
        when(diffService.diff(any())).thenReturn(diffView(previousId));

        var response = controller.diff(requestId, "previous", auth(UserRoleType.ANALYST));

        assertThat(response.previousId()).isEqualTo(previousId);
        assertThat(response.durationMsDelta()).isNull();
        assertThat(response.bytesDelta()).isEqualTo(4L);
        assertThat(response.entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.path()).isEqualTo("total");
                    assertThat(e.kind()).isEqualTo(ApiResponseDiffKind.CHANGED);
                    assertThat(e.before()).isEqualTo("1");
                    assertThat(e.after()).isEqualTo("2");
                });
        var captor = ArgumentCaptor.forClass(ApiResponseDiffCommand.class);
        verify(diffService).diff(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ApiResponseDiffCommand(requestId, null, orgId, userId,
                SystemRolePermissions.of(UserRoleType.ANALYST)));
    }

    @Test
    void diffParsesAnExplicitAgainstId() {
        var against = UUID.randomUUID();
        when(diffService.diff(any())).thenReturn(diffView(against));

        controller.diff(requestId, against.toString(), auth(UserRoleType.REVIEWER));

        var captor = ArgumentCaptor.forClass(ApiResponseDiffCommand.class);
        verify(diffService).diff(captor.capture());
        assertThat(captor.getValue().againstApiRequestId()).isEqualTo(against);
    }

    @Test
    void diffRejectsAMalformedAgainst() {
        var authentication = auth(UserRoleType.ANALYST);
        assertThatThrownBy(() -> controller.diff(requestId, "latest", authentication))
                .isInstanceOf(InvalidDiffAgainstException.class);
    }
}
