package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRequestPermissionException;
import com.bablsoft.accessflow.apigov.api.ApiRequestValidationException;
import com.bablsoft.accessflow.apigov.api.SubmitApiRequestCommand;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.events.ApiRequestSubmittedEvent;
import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiReviewDecisionRepository;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiRequestServiceTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Mock private ApiReviewDecisionRepository decisionRepository;
    @Mock private ApiSchemaService schemaService;
    @Mock private ApiRequestStateService stateService;
    @Mock private ApiExecutionService executionService;
    @Mock private BreakGlassService breakGlassService;
    @Mock private AiAnalysisLookupService aiAnalysisLookupService;
    @Mock private UserQueryService userQueryService;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DefaultApiRequestService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiRequestService(requestRepository, connectorRepository, permissionResolver,
                decisionRepository, schemaService, stateService, executionService, breakGlassService,
                aiAnalysisLookupService, userQueryService,
                new ApigovRequestProperties(5_242_880L, 10_485_760L, 65_536L),
                auditLogService, eventPublisher, JsonMapper.builder().build());
        lenient().when(schemaService.listOperations(any(), any())).thenReturn(List.of());
        lenient().when(userQueryService.findById(any())).thenReturn(Optional.empty());
    }

    private ApiConnectorEntity connector() {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setOrganizationId(orgId);
        c.setName("Stripe");
        c.setProtocol(ApiProtocol.REST);
        c.setActive(true);
        return c;
    }

    private ResolvedApiConnectorPermission permission(boolean read, boolean write, boolean breakGlass) {
        return new ResolvedApiConnectorPermission(connectorId, userId, read, write, breakGlass,
                List.of(), List.of(), null);
    }

    private SubmitApiRequestCommand cmd(String verb, SubmissionReason reason) {
        return new SubmitApiRequestCommand(connectorId, orgId, userId, false, null, verb, "/charges",
                null, null, ApiBodyType.RAW, "application/json", "{}", null, null, "need", null, reason,
                "1.2.3.4", "ua");
    }

    @Test
    void submitWritePersistsPendingAiAndPublishesEvent() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, false)));
        when(requestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.submit(cmd("POST", SubmissionReason.USER_SUBMITTED));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
        verify(eventPublisher).publishEvent(any(ApiRequestSubmittedEvent.class));
        verify(auditLogService).record(any());
    }

    @Test
    void submitWriteWithoutWritePermissionIsDenied() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, false, false)));

        assertThatThrownBy(() -> service.submit(cmd("POST", SubmissionReason.USER_SUBMITTED)))
                .isInstanceOf(ApiRequestPermissionException.class);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void submitWithoutPermissionIsDenied() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(cmd("GET", SubmissionReason.USER_SUBMITTED)))
                .isInstanceOf(ApiRequestPermissionException.class);
    }

    @Test
    void breakGlassRequiresBreakGlassPermissionThenExecutes() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, true)));
        when(requestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var executed = new ApiRequestEntity();
        executed.setId(UUID.randomUUID());
        executed.setOrganizationId(orgId);
        executed.setConnectorId(connectorId);
        executed.setSubmittedBy(userId);
        executed.setStatus(QueryStatus.EXECUTED);
        when(executionService.execute(any())).thenReturn(executed);

        var result = service.submit(cmd("POST", SubmissionReason.EMERGENCY_ACCESS));

        assertThat(result.status()).isEqualTo(QueryStatus.EXECUTED);
        verify(stateService).apply(any(), org.mockito.ArgumentMatchers.eq(QueryStatus.APPROVED));
        verify(breakGlassService).openApiBreakGlassReview(any());
        verify(eventPublisher, never()).publishEvent(any(ApiRequestSubmittedEvent.class));
    }

    @Test
    void breakGlassWithoutPermissionIsDenied() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, false)));

        assertThatThrownBy(() -> service.submit(cmd("POST", SubmissionReason.EMERGENCY_ACCESS)))
                .isInstanceOf(ApiRequestPermissionException.class);
    }

    @Test
    void cancelByNonSubmitterIsDenied() {
        var e = new ApiRequestEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setSubmittedBy(UUID.randomUUID());
        e.setStatus(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.cancel(e.getId(), orgId, userId))
                .isInstanceOf(ApiRequestPermissionException.class);
    }

    private ApiRequestEntity persisted(QueryStatus status) {
        var e = new ApiRequestEntity();
        e.setId(UUID.randomUUID());
        e.setConnectorId(connectorId);
        e.setOrganizationId(orgId);
        e.setSubmittedBy(userId);
        e.setVerb("GET");
        e.setRequestPath("/x");
        e.setStatus(status);
        return e;
    }

    @Test
    void listMapsPageWithSpecification() {
        when(requestRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<ApiRequestEntity>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(persisted(QueryStatus.PENDING_AI))));
        lenient().when(connectorRepository.findById(any())).thenReturn(Optional.of(connector()));

        var filter = new com.bablsoft.accessflow.apigov.api.ApiRequestListFilter(orgId, userId, null, null,
                null, null, null, null, null);
        var page = service.list(filter, com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).connectorName()).isEqualTo("Stripe");
    }

    @Test
    void getDeniesNonOwnerNonAdmin() {
        var e = persisted(QueryStatus.PENDING_AI);
        e.setSubmittedBy(UUID.randomUUID());
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.get(e.getId(), orgId, userId, UserRoleType.ANALYST))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException.class);
    }

    @Test
    void getReturnsDetailForOwner() {
        var e = persisted(QueryStatus.EXECUTED);
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        when(decisionRepository.findByApiRequestIdOrderByStageAscDecidedAtAsc(e.getId())).thenReturn(List.of());

        var view = service.get(e.getId(), orgId, userId, UserRoleType.ANALYST);

        assertThat(view.id()).isEqualTo(e.getId());
        assertThat(view.status()).isEqualTo(QueryStatus.EXECUTED);
    }

    @Test
    void getAllowsReviewerForOthersRequest() {
        var e = persisted(QueryStatus.PENDING_REVIEW);
        e.setSubmittedBy(UUID.randomUUID());
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        when(decisionRepository.findByApiRequestIdOrderByStageAscDecidedAtAsc(e.getId())).thenReturn(List.of());

        var view = service.get(e.getId(), orgId, userId, UserRoleType.REVIEWER);

        assertThat(view.id()).isEqualTo(e.getId());
    }

    @Test
    void getAllowsAdminForOthersRequest() {
        var e = persisted(QueryStatus.PENDING_REVIEW);
        e.setSubmittedBy(UUID.randomUUID());
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        when(decisionRepository.findByApiRequestIdOrderByStageAscDecidedAtAsc(e.getId())).thenReturn(List.of());

        var view = service.get(e.getId(), orgId, userId, UserRoleType.ADMIN);

        assertThat(view.id()).isEqualTo(e.getId());
    }

    @Test
    void downloadResponseAllowsReviewer() {
        var e = persisted(QueryStatus.EXECUTED);
        e.setSubmittedBy(UUID.randomUUID());
        e.setResponseSnapshot("{\"ok\":true}");
        e.setResponseContentType("application/json");
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));

        var payload = service.downloadResponse(e.getId(), orgId, userId, UserRoleType.REVIEWER);

        assertThat(new String(payload.content(), java.nio.charset.StandardCharsets.UTF_8)).contains("ok");
    }

    @Test
    void detailViewSlicesSnapshotToPreviewWhileDownloadKeepsFullBody() {
        service = new DefaultApiRequestService(requestRepository, connectorRepository, permissionResolver,
                decisionRepository, schemaService, stateService, executionService, breakGlassService,
                aiAnalysisLookupService, userQueryService,
                new ApigovRequestProperties(5_242_880L, 10_485_760L, 8L),
                auditLogService, eventPublisher, JsonMapper.builder().build());
        lenient().when(schemaService.listOperations(any(), any())).thenReturn(List.of());
        var full = "0123456789ABCDEF"; // 16 chars, longer than the 8-char preview budget
        var e = persisted(QueryStatus.EXECUTED);
        e.setResponseSnapshot(full);
        e.setResponseContentType("application/json");
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));
        lenient().when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        when(decisionRepository.findByApiRequestIdOrderByStageAscDecidedAtAsc(e.getId())).thenReturn(List.of());

        var view = service.get(e.getId(), orgId, userId, UserRoleType.ANALYST);
        assertThat(view.responseSnapshot()).isEqualTo("01234567");
        assertThat(view.responseSnapshotPreviewTruncated()).isTrue();

        var payload = service.downloadResponse(e.getId(), orgId, userId, UserRoleType.ANALYST);
        assertThat(new String(payload.content(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(full);
    }

    @Test
    void detailViewKeepsShortSnapshotWholeWithoutPreviewFlag() {
        var e = persisted(QueryStatus.EXECUTED);
        e.setResponseSnapshot("{\"ok\":true}");
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        when(decisionRepository.findByApiRequestIdOrderByStageAscDecidedAtAsc(e.getId())).thenReturn(List.of());

        var view = service.get(e.getId(), orgId, userId, UserRoleType.ANALYST);
        assertThat(view.responseSnapshot()).isEqualTo("{\"ok\":true}");
        assertThat(view.responseSnapshotPreviewTruncated()).isFalse();
    }

    @Test
    void executeDelegatesToExecutionServiceAndAudits() {
        var e = persisted(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));
        var executed = persisted(QueryStatus.EXECUTED);
        executed.setId(e.getId());
        when(executionService.execute(e.getId())).thenReturn(executed);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        when(decisionRepository.findByApiRequestIdOrderByStageAscDecidedAtAsc(e.getId())).thenReturn(List.of());

        var view = service.execute(e.getId(), orgId, userId, false);

        assertThat(view.status()).isEqualTo(QueryStatus.EXECUTED);
        verify(executionService).execute(e.getId());
        verify(auditLogService).record(any());
    }

    @Test
    void submitGeneratesTraceAndSpanIds() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, false)));
        var captor = org.mockito.ArgumentCaptor.forClass(ApiRequestEntity.class);
        when(requestRepository.save(captor.capture())).thenAnswer(i -> i.getArgument(0));

        service.submit(cmd("POST", SubmissionReason.USER_SUBMITTED));

        assertThat(captor.getValue().getTraceId()).hasSize(32);
        assertThat(captor.getValue().getSpanId()).hasSize(16);
    }

    @Test
    void submitRejectsBodyOverSizeCap() {
        service = new DefaultApiRequestService(requestRepository, connectorRepository, permissionResolver,
                decisionRepository, schemaService, stateService, executionService, breakGlassService,
                aiAnalysisLookupService, userQueryService,
                new ApigovRequestProperties(4L, 10_485_760L, 65_536L),
                auditLogService, eventPublisher, JsonMapper.builder().build());
        lenient().when(schemaService.listOperations(any(), any())).thenReturn(List.of());
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, false)));
        var command = new SubmitApiRequestCommand(connectorId, orgId, userId, false, null, "POST", "/charges",
                null, null, ApiBodyType.RAW, "text/plain", "way too long", List.of(), null, "need", null,
                SubmissionReason.USER_SUBMITTED, "1.2.3.4", "ua");

        assertThatThrownBy(() -> service.submit(command)).isInstanceOf(ApiRequestValidationException.class);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void listResolvesSubmitterEmail() {
        when(requestRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<ApiRequestEntity>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(persisted(QueryStatus.PENDING_AI))));
        lenient().when(connectorRepository.findById(any())).thenReturn(Optional.of(connector()));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(new UserView(userId, "u@test.io",
                "User", null, orgId, true, null, null, null, "en", false, null)));

        var filter = new com.bablsoft.accessflow.apigov.api.ApiRequestListFilter(orgId, userId, null, null,
                null, null, null, null, null);
        var page = service.list(filter, com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content().get(0).submittedByEmail()).isEqualTo("u@test.io");
    }

    @Test
    void downloadResponseReturnsSnapshotWithContentType() {
        var e = persisted(QueryStatus.EXECUTED);
        e.setResponseSnapshot("{\"ok\":true}");
        e.setResponseContentType("application/json");
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));

        var payload = service.downloadResponse(e.getId(), orgId, userId, UserRoleType.ANALYST);

        assertThat(new String(payload.content(), java.nio.charset.StandardCharsets.UTF_8)).contains("ok");
        assertThat(payload.contentType()).isEqualTo("application/json");
        assertThat(payload.filename()).endsWith(".json");
    }

    @Test
    void downloadResponseWithoutSnapshotIsRejected() {
        var e = persisted(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.downloadResponse(e.getId(), orgId, userId, UserRoleType.ANALYST))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException.class);
    }

    @Test
    void downloadResponseDeniesNonOwnerNonAdmin() {
        var e = persisted(QueryStatus.EXECUTED);
        e.setSubmittedBy(UUID.randomUUID());
        e.setResponseSnapshot("x");
        when(requestRepository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.downloadResponse(e.getId(), orgId, userId, UserRoleType.ANALYST))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException.class);
    }
}
