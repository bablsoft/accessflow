package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRequestPermissionException;
import com.bablsoft.accessflow.apigov.api.SubmitApiRequestCommand;
import com.bablsoft.accessflow.apigov.events.ApiRequestSubmittedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiReviewDecisionRepository;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
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
    @Mock private ApiConnectorUserPermissionRepository permissionRepository;
    @Mock private ApiReviewDecisionRepository decisionRepository;
    @Mock private ApiSchemaService schemaService;
    @Mock private ApiRequestStateService stateService;
    @Mock private ApiExecutionService executionService;
    @Mock private BreakGlassService breakGlassService;
    @Mock private AiAnalysisLookupService aiAnalysisLookupService;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DefaultApiRequestService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiRequestService(requestRepository, connectorRepository, permissionRepository,
                decisionRepository, schemaService, stateService, executionService, breakGlassService,
                aiAnalysisLookupService, auditLogService, eventPublisher, JsonMapper.builder().build());
        lenient().when(schemaService.listOperations(any(), any())).thenReturn(List.of());
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

    private ApiConnectorUserPermissionEntity permission(boolean read, boolean write, boolean breakGlass) {
        var p = new ApiConnectorUserPermissionEntity();
        p.setConnectorId(connectorId);
        p.setUserId(userId);
        p.setCanRead(read);
        p.setCanWrite(write);
        p.setCanBreakGlass(breakGlass);
        return p;
    }

    private SubmitApiRequestCommand cmd(String verb, SubmissionReason reason) {
        return new SubmitApiRequestCommand(connectorId, orgId, userId, false, null, verb, "/charges",
                null, "{}", "need", null, reason, "1.2.3.4", "ua");
    }

    @Test
    void submitWritePersistsPendingAiAndPublishesEvent() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId))
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
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(Optional.of(permission(true, false, false)));

        assertThatThrownBy(() -> service.submit(cmd("POST", SubmissionReason.USER_SUBMITTED)))
                .isInstanceOf(ApiRequestPermissionException.class);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void submitWithoutPermissionIsDenied() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(cmd("GET", SubmissionReason.USER_SUBMITTED)))
                .isInstanceOf(ApiRequestPermissionException.class);
    }

    @Test
    void breakGlassRequiresBreakGlassPermissionThenExecutes() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector()));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId))
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
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId))
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
}
