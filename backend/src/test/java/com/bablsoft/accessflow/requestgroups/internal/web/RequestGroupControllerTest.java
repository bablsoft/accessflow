package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupListFilter;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupSubmissionResult;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.UpdateRequestGroupCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestGroupControllerTest {

    private RequestGroupService service;
    private RequestGroupController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("1.2.3.4", "ua");

    @BeforeEach
    void setUp() {
        service = mock(RequestGroupService.class);
        controller = new RequestGroupController(service);
    }

    private Authentication auth(UserRoleType role) {
        var a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(new JwtClaims(userId, "u@acme.test", role, orgId));
        return a;
    }

    private RequestGroupItemView itemView() {
        return new RequestGroupItemView(UUID.randomUUID(), 0, RequestGroupTargetKind.QUERY,
                UUID.randomUUID(), "db", "SELECT 1", QueryType.SELECT, false, null, null, null, null,
                null, Map.of(), Map.of(), null, null, null, List.of(), null,
                null, RiskLevel.LOW, 10, analysisDetail(), RequestGroupItemStatus.PENDING, null,
                null, null, null, null);
    }

    private QueryDetailView.AiAnalysisDetail analysisDetail() {
        return new QueryDetailView.AiAnalysisDetail(UUID.randomUUID(), RiskLevel.LOW, 10,
                "Reads one row", "[{\"severity\":\"LOW\"}]", null, false, 1L, AiProviderType.OPENAI,
                "gpt-4o", 12, 7, false, null);
    }

    private RequestGroupView view(RequestGroupStatus status) {
        return new RequestGroupView(groupId, orgId, userId, "Dana", "bundle", "desc", status, false,
                null, RiskLevel.LOW, 10, 1, 1, null, null, null, Instant.now(), Instant.now(),
                List.of(itemView()));
    }

    private CreateRequestGroupRequest createBody() {
        var query = new RequestGroupItemRequest(RequestGroupTargetKind.QUERY, UUID.randomUUID(),
                "SELECT 1", false, null, null, null, null, null, null, null, null, null, null, null);
        var api = new RequestGroupItemRequest(RequestGroupTargetKind.API_CALL, null, null, false,
                UUID.randomUUID(), "op", "POST", "/x", Map.of("h", "v"), Map.of("q", "1"),
                RequestGroupItemInput.ApiBodyKind.RAW, "application/json", "{}",
                List.of(new ApiFormField("f", ApiFormField.ApiFormFieldType.TEXT, "v", null, null)), null);
        return new CreateRequestGroupRequest("bundle", "desc", true, List.of(query, api));
    }

    @Test
    void createDelegatesAndMapsToCommand() {
        when(service.createDraft(any())).thenReturn(view(RequestGroupStatus.DRAFT));
        var resp = controller.create(createBody(), auth(UserRoleType.ANALYST));

        assertThat(resp.status()).isEqualTo(RequestGroupStatus.DRAFT);
        assertThat(resp.items()).hasSize(1);
        var captor = ArgumentCaptor.forClass(CreateRequestGroupCommand.class);
        verify(service).createDraft(captor.capture());
        assertThat(captor.getValue().items()).hasSize(2);
        assertThat(captor.getValue().items().get(0).targetKind()).isEqualTo(RequestGroupTargetKind.QUERY);
        assertThat(captor.getValue().items().get(1).targetKind()).isEqualTo(RequestGroupTargetKind.API_CALL);
        assertThat(captor.getValue().items().get(1).formFields()).hasSize(1);
        assertThat(captor.getValue().items().get(1).formFields().get(0).name()).isEqualTo("f");
        assertThat(captor.getValue().items().get(1).formFields().get(0).file()).isFalse();
    }

    @Test
    void listScopesNonAdminToOwnSubmissions() {
        when(service.list(any(), any())).thenReturn(new PageResponse<>(List.of(view(RequestGroupStatus.APPROVED)),
                0, 20, 1, 1));
        var resp = controller.list(auth(UserRoleType.ANALYST), Pageable.ofSize(20),
                RequestGroupStatus.APPROVED, UUID.randomUUID());

        assertThat(resp.content()).hasSize(1);
        var captor = ArgumentCaptor.forClass(RequestGroupListFilter.class);
        verify(service).list(captor.capture(), any());
        assertThat(captor.getValue().submittedByUserId()).isEqualTo(userId);
    }

    @Test
    void listLetsAdminFilterBySubmitter() {
        var other = UUID.randomUUID();
        when(service.list(any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        controller.list(auth(UserRoleType.ADMIN), Pageable.ofSize(20), null, other);

        var captor = ArgumentCaptor.forClass(RequestGroupListFilter.class);
        verify(service).list(captor.capture(), any());
        assertThat(captor.getValue().submittedByUserId()).isEqualTo(other);
    }

    @Test
    void getDelegates() {
        when(service.get(groupId, orgId, userId, false)).thenReturn(view(RequestGroupStatus.PENDING_REVIEW));
        var resp = controller.get(groupId, auth(UserRoleType.ANALYST));
        assertThat(resp.status()).isEqualTo(RequestGroupStatus.PENDING_REVIEW);
        var analysis = resp.items().get(0).aiAnalysis();
        assertThat(analysis).isNotNull();
        assertThat(analysis.summary()).isEqualTo("Reads one row");
        assertThat(analysis.issues()).isEqualTo("[{\"severity\":\"LOW\"}]");
        assertThat(analysis.optimizations()).isEqualTo("[]");
    }

    @Test
    void updateMapsToCommand() {
        when(service.updateDraft(any())).thenReturn(view(RequestGroupStatus.DRAFT));
        var body = new UpdateRequestGroupRequest("renamed", null, false,
                List.of(new RequestGroupItemRequest(RequestGroupTargetKind.QUERY, UUID.randomUUID(),
                        "SELECT 2", true, null, null, null, null, null, null, null, null, null, null, null)));
        controller.update(groupId, body, auth(UserRoleType.ANALYST));

        var captor = ArgumentCaptor.forClass(UpdateRequestGroupCommand.class);
        verify(service).updateDraft(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("renamed");
        assertThat(captor.getValue().items().get(0).transactional()).isTrue();
    }

    @Test
    void deleteDelegates() {
        controller.delete(groupId, auth(UserRoleType.ANALYST));
        verify(service).deleteDraft(groupId, orgId, userId);
    }

    @Test
    void submitMapsCommandAndReturnsFreshView() {
        when(service.submit(any())).thenReturn(new RequestGroupSubmissionResult(groupId,
                RequestGroupStatus.PENDING_AI));
        when(service.get(groupId, orgId, userId, false)).thenReturn(view(RequestGroupStatus.PENDING_AI));
        var body = new SubmitRequestGroupRequest(true, Instant.now());
        controller.submit(groupId, body, auth(UserRoleType.ANALYST), auditContext);

        var captor = ArgumentCaptor.forClass(SubmitRequestGroupCommand.class);
        verify(service).submit(captor.capture());
        assertThat(captor.getValue().breakGlass()).isTrue();
        assertThat(captor.getValue().submittedIp()).isEqualTo("1.2.3.4");
    }

    @Test
    void executeDelegates() {
        when(service.execute(groupId, orgId, userId, true)).thenReturn(view(RequestGroupStatus.EXECUTED));
        assertThat(controller.execute(groupId, auth(UserRoleType.ADMIN)).status())
                .isEqualTo(RequestGroupStatus.EXECUTED);
    }

    @Test
    void cancelDelegatesThenReturnsView() {
        when(service.get(groupId, orgId, userId, false)).thenReturn(view(RequestGroupStatus.CANCELLED));
        var resp = controller.cancel(groupId, auth(UserRoleType.ANALYST));
        verify(service).cancel(groupId, orgId, userId);
        assertThat(resp.status()).isEqualTo(RequestGroupStatus.CANCELLED);
    }
}
