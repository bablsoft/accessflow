package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.UserSlackMappingEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserSlackMappingRepository;
import com.bablsoft.accessflow.workflow.api.QueryNotPendingReviewException;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewerNotEligibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackInteractionServiceTest {

    @Mock UserSlackMappingRepository mappingRepository;
    @Mock UserQueryService userQueryService;
    @Mock ReviewService reviewService;
    @Mock SlackResponseSender responseSender;
    @Mock SlackMessages messages;

    private SlackInteractionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();
    private final DecryptedSlackApp app =
            new DecryptedSlackApp(UUID.randomUUID(), "A1", "xoxb", "sign", "C1");

    @BeforeEach
    void setUp() {
        service = new SlackInteractionService(mappingRepository, userQueryService, reviewService,
                (roleId, fallback) -> fallback != null
                        ? SystemRolePermissions.of(fallback) : java.util.Set.of(),
                responseSender, messages);
        lenient().when(messages.forOrg(any(), anyString())).thenAnswer(i -> i.getArgument(1));
        lenient().when(messages.forOrg(any(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
    }

    private DecryptedSlackApp app() {
        return new DecryptedSlackApp(orgId, "A1", "xoxb", "sign", "C1");
    }

    private void linkUser() {
        var mapping = new UserSlackMappingEntity();
        mapping.setUserId(userId);
        when(mappingRepository.findByOrganizationIdAndSlackUserId(orgId, "U1"))
                .thenReturn(Optional.of(mapping));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(
                new UserView(userId, "rev@example.com", "Rev", UserRoleType.REVIEWER, orgId,
                        true, AuthProviderType.LOCAL, null, null, "en", false, Instant.now())));
    }

    @Test
    void rejectsUnknownSlackUser() {
        when(mappingRepository.findByOrganizationIdAndSlackUserId(orgId, "U1"))
                .thenReturn(Optional.empty());

        service.handleAction(app(), "U1", "approve", queryId.toString(), "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.not_linked");
        verify(reviewService, never()).approve(any(), any(), any());
    }

    @Test
    void rejectsUnrecognizedAction() {
        linkUser();
        service.handleAction(app(), "U1", "frobnicate", queryId.toString(), "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.invalid");
        verify(reviewService, never()).approve(any(), any(), any());
    }

    @Test
    void rejectsInvalidQueryId() {
        linkUser();
        service.handleAction(app(), "U1", "approve", "not-a-uuid", "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.invalid");
    }

    @Test
    void approveCallsReviewServiceAndReplacesMessage() {
        linkUser();
        service.handleAction(app(), "U1", "approve", queryId.toString(), "https://resp");

        var ctxCaptor = ArgumentCaptor.forClass(ReviewService.ReviewerContext.class);
        verify(reviewService).approve(eq(queryId), ctxCaptor.capture(), eq("slack.action.approve_comment"));
        assertThat(ctxCaptor.getValue().userId()).isEqualTo(userId);
        assertThat(ctxCaptor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(ctxCaptor.getValue().roleName()).isEqualTo("REVIEWER");

        var payload = capturePayload();
        assertThat(payload.get("replace_original")).isEqualTo(true);
        assertThat(payload.get("text")).isEqualTo("slack.action.approved");
    }

    @Test
    void rejectCallsReviewServiceAndReplacesMessage() {
        linkUser();
        service.handleAction(app(), "U1", "reject", queryId.toString(), "https://resp");

        verify(reviewService).reject(eq(queryId), any(), eq("slack.action.reject_comment"));
        assertThat(capturePayload().get("text")).isEqualTo("slack.action.rejected");
    }

    @Test
    void selfApprovalBlockedShowsEphemeral() {
        linkUser();
        when(reviewService.approve(any(), any(), any()))
                .thenThrow(new AccessDeniedException("self"));

        service.handleAction(app(), "U1", "approve", queryId.toString(), "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.self_approval");
    }

    @Test
    void notEligibleShowsEphemeral() {
        linkUser();
        when(reviewService.approve(any(), any(), any()))
                .thenThrow(new ReviewerNotEligibleException(userId, queryId));

        service.handleAction(app(), "U1", "approve", queryId.toString(), "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.not_eligible");
    }

    @Test
    void notPendingShowsEphemeral() {
        linkUser();
        when(reviewService.reject(any(), any(), any()))
                .thenThrow(new QueryNotPendingReviewException(queryId, QueryStatus.APPROVED));

        service.handleAction(app(), "U1", "reject", queryId.toString(), "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.not_pending");
    }

    @Test
    void notFoundShowsEphemeral() {
        linkUser();
        when(reviewService.approve(any(), any(), any()))
                .thenThrow(new QueryRequestNotFoundException(queryId));

        service.handleAction(app(), "U1", "approve", queryId.toString(), "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.not_found");
    }

    @Test
    void unexpectedErrorShowsGenericEphemeral() {
        linkUser();
        when(reviewService.approve(any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        service.handleAction(app(), "U1", "approve", queryId.toString(), "https://resp");

        assertThat(ephemeralText()).isEqualTo("slack.action.error");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload() {
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(responseSender).send(eq("https://resp"), captor.capture());
        return captor.getValue();
    }

    private String ephemeralText() {
        var payload = capturePayload();
        assertThat(payload.get("response_type")).isEqualTo("ephemeral");
        return (String) payload.get("text");
    }
}
