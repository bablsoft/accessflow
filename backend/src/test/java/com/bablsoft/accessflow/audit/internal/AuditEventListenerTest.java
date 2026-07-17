package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.audit.events.NotificationDeliveryExhaustedEvent;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import com.bablsoft.accessflow.core.events.SecretReferenceResolutionFailedEvent;
import com.bablsoft.accessflow.core.events.SecretReferenceResolvedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventListenerTest {

    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final QueryRequestLookupService queryRequestLookupService = mock(QueryRequestLookupService.class);
    private final DatasourceLookupService datasourceLookupService = mock(DatasourceLookupService.class);
    private final AuditEventListener listener = new AuditEventListener(
            auditLogService, queryRequestLookupService, datasourceLookupService);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();

    private QueryRequestSnapshot snapshot(UUID queryId) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_REVIEW, null,
                null, null, false);
    }

    @Test
    void onAiCompletedRecordsQueryAiAnalyzed() {
        var queryId = UUID.randomUUID();
        var aiAnalysisId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId, RiskLevel.HIGH));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_AI_ANALYZED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(queryId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("risk_level", "HIGH");
        assertThat(entry.metadata()).containsEntry("ai_analysis_id", aiAnalysisId.toString());
    }

    @Test
    void onAiFailedRecordsQueryAiFailedWithReason() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onAiFailed(new AiAnalysisFailedEvent(queryId, "model-timeout"));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_AI_FAILED);
        assertThat(entry.metadata()).containsEntry("reason", "model-timeout");
    }

    @Test
    void onQueryReadyForReviewRecordsRow() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        assertThat(captor.getValue().action()).isEqualTo(AuditAction.QUERY_REVIEW_REQUESTED);
        assertThat(captor.getValue().actorId()).isNull();
        assertThat(captor.getValue().metadata()).doesNotContainKey("routing_policy_id");
    }

    @Test
    void onQueryReadyForReviewRecordsMatchedPolicyWhenEscalated() {
        var queryId = UUID.randomUUID();
        var policyId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryReadyForReview(
                new QueryReadyForReviewEvent(queryId, policyId, "off-network", 3));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_REVIEW_REQUESTED);
        assertThat(entry.metadata()).containsEntry("source", "ROUTING_POLICY");
        assertThat(entry.metadata()).containsEntry("routing_policy_id", policyId.toString());
        assertThat(entry.metadata()).containsEntry("effective_min_approvals", 3);
        assertThat(entry.metadata()).containsEntry("reason", "off-network");
    }

    @Test
    void onQueryAutoApprovedRecordsApprovedWithMetadata() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryAutoApproved(new QueryAutoApprovedEvent(queryId));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_APPROVED);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("auto_approved", true);
        assertThat(entry.metadata()).doesNotContainKey("routing_policy_id");
    }

    @Test
    void onQueryAutoApprovedRecordsRoutingPolicyProvenance() {
        var queryId = UUID.randomUUID();
        var policyId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryAutoApproved(new QueryAutoApprovedEvent(queryId, policyId, "trusted"));

        var entry = captor.getValue();
        assertThat(entry.metadata()).containsEntry("auto_approved", true);
        assertThat(entry.metadata()).containsEntry("source", "ROUTING_POLICY");
        assertThat(entry.metadata()).containsEntry("routing_policy_id", policyId.toString());
        assertThat(entry.metadata()).containsEntry("reason", "trusted");
    }

    @Test
    void onQueryAutoApprovedRecordsAccessGrantProvenance() {
        var queryId = UUID.randomUUID();
        var grantId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryAutoApproved(new QueryAutoApprovedEvent(queryId, null,
                "grant-covered", grantId, "approver@x.io"));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_APPROVED);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("auto_approved", true);
        assertThat(entry.metadata()).containsEntry("source", "ACCESS_GRANT");
        assertThat(entry.metadata()).containsEntry("access_grant_id", grantId.toString());
        assertThat(entry.metadata()).containsEntry("grant_approver", "approver@x.io");
        assertThat(entry.metadata()).containsEntry("reason", "grant-covered");
        assertThat(entry.metadata()).doesNotContainKey("routing_policy_id");
    }

    @Test
    void onQueryAutoApprovedRecordsReasonOnlyProvenanceForExternalTickets() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryAutoApproved(new QueryAutoApprovedEvent(queryId, null,
                "ServiceNow ticket INC1 resolved", null, null));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_APPROVED);
        assertThat(entry.metadata()).containsEntry("auto_approved", true);
        assertThat(entry.metadata()).containsEntry("reason", "ServiceNow ticket INC1 resolved");
        assertThat(entry.metadata()).doesNotContainKey("source");
        assertThat(entry.metadata()).doesNotContainKey("routing_policy_id");
        assertThat(entry.metadata()).doesNotContainKey("access_grant_id");
    }

    @Test
    void onQueryAutoRejectedRecordsRejectedWithPolicyProvenance() {
        var queryId = UUID.randomUUID();
        var policyId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryAutoRejected(
                new com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent(queryId, policyId,
                        "payroll deletes blocked"));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_REJECTED);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("auto_rejected", true);
        assertThat(entry.metadata()).containsEntry("source", "ROUTING_POLICY");
        assertThat(entry.metadata()).containsEntry("routing_policy_id", policyId.toString());
        assertThat(entry.metadata()).containsEntry("reason", "payroll deletes blocked");
    }

    @Test
    void onQueryAutoRejectedWithoutPolicyRecordsReasonWithoutRoutingSource() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryAutoRejected(
                new com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent(queryId, null,
                        "Jira ticket AF-1 declined"));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_REJECTED);
        assertThat(entry.metadata()).containsEntry("auto_rejected", true);
        assertThat(entry.metadata()).containsEntry("reason", "Jira ticket AF-1 declined");
        assertThat(entry.metadata()).doesNotContainKey("source");
        assertThat(entry.metadata()).doesNotContainKey("routing_policy_id");
    }

    @Test
    void onQueryTimedOutRecordsRejectedWithAutoMetadata() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryTimedOut(new QueryTimedOutEvent(queryId, 24));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_REJECTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(queryId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("auto_rejected", true);
        assertThat(entry.metadata()).containsEntry("reason", "approval_timeout");
        assertThat(entry.metadata()).containsEntry("timeout_hours", 24);
    }

    @Test
    void onDatasourceDeactivatedRecordsUpdatedWithChange() {
        var descriptor = new DatasourceConnectionDescriptor(datasourceId, organizationId,
                DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 5, 1000,
                false, null, false, null, null, null, null, null, null, false);
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onDatasourceDeactivated(new DatasourceDeactivatedEvent(datasourceId));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DATASOURCE_UPDATED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DATASOURCE);
        assertThat(entry.resourceId()).isEqualTo(datasourceId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.metadata()).containsEntry("change", "deactivated");
    }

    @Test
    void onSecretReferenceResolvedWithContextRecordsDirectly() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onSecretReferenceResolved(new SecretReferenceResolvedEvent(
                "vault", "vault:secret/prod/db#password", datasourceId, organizationId));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DATASOURCE_SECRET_RESOLVED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DATASOURCE);
        assertThat(entry.resourceId()).isEqualTo(datasourceId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("provider", "vault");
        assertThat(entry.metadata()).containsEntry("reference", "vault:secret/prod/db#password");
        assertThat(entry.metadata()).doesNotContainKey("error");
        verify(datasourceLookupService, never()).findByCredentialReference(any());
    }

    @Test
    void onSecretReferenceResolutionFailedRecordsErrorMetadata() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onSecretReferenceResolutionFailed(new SecretReferenceResolutionFailedEvent(
                "aws", "aws:prod/db#password", datasourceId, organizationId, "store unreachable"));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DATASOURCE_SECRET_RESOLUTION_FAILED);
        assertThat(entry.metadata()).containsEntry("error", "store unreachable");
    }

    @Test
    void contextlessSecretResolveIsAttributedViaCredentialReferenceLookup() {
        var descriptor = new DatasourceConnectionDescriptor(datasourceId, organizationId,
                DbType.MONGODB, "h", 27017, "db", "u", "vault:secret/prod/db#password",
                SslMode.DISABLE, 5, 1000, false, null, false, null, null, null, null, null,
                null, false);
        when(datasourceLookupService.findByCredentialReference("vault:secret/prod/db#password"))
                .thenReturn(List.of(descriptor));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onSecretReferenceResolved(new SecretReferenceResolvedEvent(
                "vault", "vault:secret/prod/db#password", null, null));

        var entry = captor.getValue();
        assertThat(entry.resourceId()).isEqualTo(datasourceId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
    }

    @Test
    void unattributableContextlessSecretResolveIsSkipped() {
        when(datasourceLookupService.findByCredentialReference("vault:secret/gone#password"))
                .thenReturn(List.of());

        listener.onSecretReferenceResolved(new SecretReferenceResolvedEvent(
                "vault", "vault:secret/gone#password", null, null));

        verify(auditLogService, never()).record(any());
    }

    @Test
    void secretResolveAuditFailureIsSwallowed() {
        when(auditLogService.record(any())).thenThrow(new RuntimeException("db down"));

        listener.onSecretReferenceResolved(new SecretReferenceResolvedEvent(
                "vault", "vault:secret/prod/db#password", datasourceId, organizationId));
        // No exception should propagate.
    }

    @Test
    void unknownQueryIdSkipsAuditRow() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        listener.onAiCompleted(new AiAnalysisCompletedEvent(queryId, UUID.randomUUID(), RiskLevel.LOW));

        verify(auditLogService, never()).record(any());
    }

    @Test
    void runtimeFailureFromAuditServiceIsSwallowed() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        when(auditLogService.record(any())).thenThrow(new RuntimeException("db down"));

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));
        // No exception should propagate.
    }

    @Test
    void datasourceDeactivatedSkipsWhenLookupReturnsEmpty() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());

        listener.onDatasourceDeactivated(new DatasourceDeactivatedEvent(datasourceId));

        verify(auditLogService, never()).record(any());
    }

    @Test
    void onBootstrapDatasourceUpdatedRecordsRowWithSourceMetadata() {
        var resourceId = UUID.randomUUID();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId,
                BootstrapResourceType.DATASOURCE,
                resourceId,
                BootstrapChangeKind.UPDATE,
                List.of("host", "port"),
                Map.of("name", "prod-pg", "db_type", "POSTGRESQL")));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DATASOURCE_UPDATED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DATASOURCE);
        assertThat(entry.resourceId()).isEqualTo(resourceId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.ipAddress()).isNull();
        assertThat(entry.userAgent()).isNull();
        assertThat(entry.metadata())
                .containsEntry("source", "BOOTSTRAP")
                .containsEntry("change_kind", "UPDATE")
                .containsEntry("changed_fields", List.of("host", "port"))
                .containsEntry("name", "prod-pg");
    }

    @Test
    void onBootstrapOrganizationCreatedMapsToOrganizationAction() {
        var resourceId = UUID.randomUUID();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId,
                BootstrapResourceType.ORGANIZATION,
                resourceId,
                BootstrapChangeKind.CREATE,
                List.of(),
                Map.of("name", "Acme")));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.ORGANIZATION_CREATED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.ORGANIZATION);
        assertThat(entry.metadata()).containsEntry("change_kind", "CREATE");
        assertThat(entry.metadata()).doesNotContainKey("changed_fields");
    }

    @Test
    void onBootstrapNotificationChannelCreateAndUpdateUseDistinctActions() {
        var resourceId = UUID.randomUUID();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId,
                BootstrapResourceType.NOTIFICATION_CHANNEL,
                resourceId,
                BootstrapChangeKind.CREATE,
                List.of(),
                Map.of()));
        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId,
                BootstrapResourceType.NOTIFICATION_CHANNEL,
                resourceId,
                BootstrapChangeKind.UPDATE,
                List.of("config"),
                Map.of()));

        assertThat(captor.getAllValues()).extracting(AuditEntry::action)
                .containsExactly(AuditAction.NOTIFICATION_CHANNEL_CREATED,
                        AuditAction.NOTIFICATION_CHANNEL_UPDATED);
    }

    @Test
    void onBootstrapServiceAccountMapsToApiKeyActionsAndResourceType() {
        var resourceId = UUID.randomUUID();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId, BootstrapResourceType.SERVICE_ACCOUNT, resourceId,
                BootstrapChangeKind.CREATE, List.of(),
                Map.of("email", "ci@acme.com", "api_key_name", "terraform")));
        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId, BootstrapResourceType.SERVICE_ACCOUNT, resourceId,
                BootstrapChangeKind.UPDATE, List.of(), Map.of("api_key_name", "terraform")));

        assertThat(captor.getAllValues()).extracting(AuditEntry::action)
                .containsExactly(AuditAction.API_KEY_CREATED, AuditAction.API_KEY_UPDATED);
        assertThat(captor.getAllValues()).extracting(AuditEntry::resourceType)
                .containsOnly(AuditResourceType.API_KEY);
    }

    @Test
    void onBootstrapSamlAndOauthAndSmtpAlwaysEmitUpdateAction() {
        var resourceId = UUID.randomUUID();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId, BootstrapResourceType.SAML_CONFIG, resourceId,
                BootstrapChangeKind.UPDATE, List.of(), Map.of()));
        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId, BootstrapResourceType.OAUTH2_CONFIG, resourceId,
                BootstrapChangeKind.UPDATE, List.of(), Map.of("provider", "GOOGLE")));
        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId, BootstrapResourceType.SYSTEM_SMTP, resourceId,
                BootstrapChangeKind.UPDATE, List.of(), Map.of()));

        assertThat(captor.getAllValues()).extracting(AuditEntry::action)
                .containsExactly(AuditAction.SAML_CONFIG_UPDATED,
                        AuditAction.OAUTH2_CONFIG_UPDATED,
                        AuditAction.SYSTEM_SMTP_UPDATED);
    }

    @Test
    void onNotificationDeliveryExhaustedRecordsAuditRow() {
        var channelId = UUID.randomUUID();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onNotificationDeliveryExhausted(new NotificationDeliveryExhaustedEvent(
                organizationId,
                channelId,
                "WEBHOOK",
                "QUERY_APPROVED",
                4,
                503,
                "503 Service Unavailable"));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.NOTIFICATION_DELIVERY_EXHAUSTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.NOTIFICATION_CHANNEL);
        assertThat(entry.resourceId()).isEqualTo(channelId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.ipAddress()).isNull();
        assertThat(entry.userAgent()).isNull();
        assertThat(entry.metadata())
                .containsEntry("source", "DISPATCHER")
                .containsEntry("channel_id", channelId.toString())
                .containsEntry("channel_type", "WEBHOOK")
                .containsEntry("event_type", "QUERY_APPROVED")
                .containsEntry("attempt_count", 4)
                .containsEntry("last_http_status", 503)
                .containsEntry("last_error", "503 Service Unavailable");
    }

    @Test
    void onNotificationDeliveryExhaustedOmitsHttpStatusAndErrorWhenNull() {
        var channelId = UUID.randomUUID();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onNotificationDeliveryExhausted(new NotificationDeliveryExhaustedEvent(
                organizationId,
                channelId,
                "WEBHOOK",
                "QUERY_SUBMITTED",
                4,
                null,
                null));

        var metadata = captor.getValue().metadata();
        assertThat(metadata).doesNotContainKey("last_http_status");
        assertThat(metadata).doesNotContainKey("last_error");
        assertThat(metadata).containsEntry("attempt_count", 4);
    }

    @Test
    void onNotificationDeliveryExhaustedSwallowsAuditFailure() {
        when(auditLogService.record(any())).thenThrow(new RuntimeException("db down"));

        listener.onNotificationDeliveryExhausted(new NotificationDeliveryExhaustedEvent(
                organizationId,
                UUID.randomUUID(),
                "WEBHOOK",
                "QUERY_REJECTED",
                4,
                500,
                "boom"));
        // No exception should propagate.
    }

    @Test
    void onBootstrapRuntimeFailureIsSwallowed() {
        when(auditLogService.record(any())).thenThrow(new RuntimeException("db down"));

        listener.onBootstrapResourceUpserted(new BootstrapResourceUpsertedEvent(
                organizationId, BootstrapResourceType.AI_CONFIG, UUID.randomUUID(),
                BootstrapChangeKind.CREATE, List.of(), Map.of()));
        // No exception should propagate.
    }
}
