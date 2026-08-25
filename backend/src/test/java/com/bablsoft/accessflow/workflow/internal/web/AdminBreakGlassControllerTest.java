package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.BreakGlassAdminService;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventView;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the acknowledge audit's target-kind branch (#695). Before it existed the method
 * dereferenced the query fields unconditionally, NPE'd for API and deployment targets, and the
 * swallow dropped the row — nothing else covered this path, which is why it went unnoticed.
 */
@ExtendWith(MockitoExtension.class)
class AdminBreakGlassControllerTest {

    @Mock private BreakGlassAdminService breakGlassAdminService;
    @Mock private AuditLogService auditLogService;

    private AdminBreakGlassController controller;

    private final UUID eventId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final RequestAuditContext auditContext =
            new RequestAuditContext("10.0.0.9", "browser/1.0");

    @BeforeEach
    void setUp() {
        controller = new AdminBreakGlassController(breakGlassAdminService, auditLogService);
    }

    @Test
    void acknowledgingAQueryEventAuditsTheGenericAction() {
        var queryRequestId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        when(breakGlassAdminService.acknowledge(orgId, eventId, actorId, "ok"))
                .thenReturn(view(queryRequestId, null, null, datasourceId, null, null));

        controller.acknowledge(eventId, new AcknowledgeBreakGlassRequest("ok"), orgId, actorId,
                auditContext);

        var entry = capturedEntry();
        assertThat(entry.action()).isEqualTo(AuditAction.BREAK_GLASS_REVIEWED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.BREAK_GLASS_EVENT);
        assertThat(entry.metadata())
                .containsEntry("query_request_id", queryRequestId.toString())
                .containsEntry("datasource_id", datasourceId.toString())
                .containsEntry("submitted_by", submitterId.toString());
        assertThat(entry.ipAddress()).isEqualTo("10.0.0.9");
        assertThat(entry.userAgent()).isEqualTo("browser/1.0");
    }

    @Test
    void acknowledgingADeploymentEventAuditsTheDeploymentAction() {
        var deploymentRequestId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        when(breakGlassAdminService.acknowledge(orgId, eventId, actorId, null))
                .thenReturn(view(null, null, deploymentRequestId, null, null, pipelineId));

        controller.acknowledge(eventId, null, orgId, actorId, auditContext);

        var entry = capturedEntry();
        assertThat(entry.action()).isEqualTo(AuditAction.DEPLOYMENT_BREAK_GLASS_REVIEWED);
        assertThat(entry.metadata())
                .containsEntry("deployment_request_id", deploymentRequestId.toString())
                .containsEntry("pipeline_id", pipelineId.toString())
                .containsEntry("submitted_by", submitterId.toString());
    }

    @Test
    void acknowledgingAnApiEventAuditsTheApiAction() {
        var apiRequestId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        when(breakGlassAdminService.acknowledge(orgId, eventId, actorId, null))
                .thenReturn(view(null, apiRequestId, null, null, connectorId, null));

        controller.acknowledge(eventId, null, orgId, actorId, auditContext);

        var entry = capturedEntry();
        assertThat(entry.action()).isEqualTo(AuditAction.API_BREAK_GLASS_REVIEWED);
        assertThat(entry.metadata())
                .containsEntry("api_request_id", apiRequestId.toString())
                .containsEntry("connector_id", connectorId.toString());
    }

    @Test
    void anAuditFailureNeverBreaksTheAcknowledgment() {
        when(breakGlassAdminService.acknowledge(orgId, eventId, actorId, null))
                .thenReturn(view(UUID.randomUUID(), null, null, UUID.randomUUID(), null, null));
        when(auditLogService.record(any())).thenThrow(new IllegalStateException("audit down"));

        assertThatCode(() -> controller.acknowledge(eventId, null, orgId, actorId, auditContext))
                .doesNotThrowAnyException();
    }

    private AuditEntry capturedEntry() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    private BreakGlassEventView view(UUID queryRequestId, UUID apiRequestId,
                                     UUID deploymentRequestId, UUID datasourceId,
                                     UUID connectorId, UUID pipelineId) {
        return new BreakGlassEventView(eventId, queryRequestId, apiRequestId, deploymentRequestId,
                orgId, datasourceId, null, connectorId, pipelineId, submitterId, "Dev",
                "dev@example.com", null, QueryStatus.EXECUTED, "incident 42",
                BreakGlassStatus.REVIEWED, actorId, "Admin", "ok", Instant.now(), Instant.now());
    }
}
