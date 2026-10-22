package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketProvenance;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentExternalDecisionServiceTest {

    @Mock DeploymentRequestRepository repository;
    @Mock DeploymentRequestStateService stateService;
    @Mock DeploygovAuditWriter auditWriter;
    @Mock ApplicationEventPublisher eventPublisher;

    private DefaultDeploymentExternalDecisionService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDeploymentExternalDecisionService(repository, stateService,
                auditWriter, eventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void approveFromPendingReviewTransitionsAuditsAndPublishes() {
        var request = request(QueryStatus.PENDING_REVIEW);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        var applied = service.applyTicketDecision(requestId, orgId, DecisionType.APPROVED,
                provenance("jdoe"));

        assertThat(applied).isTrue();
        verify(stateService).apply(request, QueryStatus.APPROVED);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq(AuditAction.DEPLOYMENT_APPROVED),
                eq(AuditResourceType.DEPLOYMENT_REQUEST), eq(requestId), eq(orgId), isNull(),
                metadata.capture(), isNull());
        assertThat((Map<String, Object>) metadata.getValue()).containsExactly(
                Map.entry("trigger", "ticket"),
                Map.entry("ticket_system", "JIRA"),
                Map.entry("external_key", "OPS-7"),
                Map.entry("channel_id", channelId.toString()),
                Map.entry("actor", "jdoe"),
                Map.entry("pipeline_id", pipelineId.toString()),
                Map.entry("version", "2.4.1"));
        verify(eventPublisher).publishEvent(new DeploymentDecidedEvent(requestId,
                QueryStatus.APPROVED, "ticket:JIRA:OPS-7"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectFromPendingReviewOmitsNullActor() {
        var request = request(QueryStatus.PENDING_REVIEW);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        var applied = service.applyTicketDecision(requestId, orgId, DecisionType.REJECTED,
                provenance(null));

        assertThat(applied).isTrue();
        verify(stateService).apply(request, QueryStatus.REJECTED);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq(AuditAction.DEPLOYMENT_REJECTED),
                eq(AuditResourceType.DEPLOYMENT_REQUEST), eq(requestId), eq(orgId), isNull(),
                metadata.capture(), isNull());
        assertThat((Map<String, Object>) metadata.getValue()).doesNotContainKey("actor")
                .containsEntry("trigger", "ticket");
        verify(eventPublisher).publishEvent(new DeploymentDecidedEvent(requestId,
                QueryStatus.REJECTED, "ticket:JIRA:OPS-7"));
    }

    @Test
    void unknownRequestReturnsFalse() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.empty());

        assertThat(service.applyTicketDecision(requestId, orgId, DecisionType.APPROVED,
                provenance("jdoe"))).isFalse();
        verifyNoInteractions(stateService, auditWriter, eventPublisher);
    }

    @Test
    void otherOrganizationReturnsFalse() {
        when(repository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(request(QueryStatus.PENDING_REVIEW)));

        assertThat(service.applyTicketDecision(requestId, UUID.randomUUID(),
                DecisionType.APPROVED, provenance("jdoe"))).isFalse();
        verifyNoInteractions(stateService, auditWriter, eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(value = QueryStatus.class,
            names = {"APPROVED", "REJECTED", "CANCELLED", "TIMED_OUT"})
    void notPendingReviewReturnsFalse(QueryStatus status) {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request(status)));

        assertThat(service.applyTicketDecision(requestId, orgId, DecisionType.REJECTED,
                provenance("jdoe"))).isFalse();
        verifyNoInteractions(stateService, auditWriter, eventPublisher);
    }

    @Test
    void racedTransitionReturnsFalseWithoutAuditOrEvent() {
        var request = request(QueryStatus.PENDING_REVIEW);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        doThrow(new IllegalDeploymentRequestStateException(QueryStatus.APPROVED,
                QueryStatus.REJECTED)).when(stateService).apply(request, QueryStatus.REJECTED);

        assertThat(service.applyTicketDecision(requestId, orgId, DecisionType.REJECTED,
                provenance("jdoe"))).isFalse();
        verifyNoInteractions(auditWriter);
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(Object.class));
    }

    @Test
    void requestedChangesIsRejected() {
        assertThatThrownBy(() -> service.applyTicketDecision(requestId, orgId,
                DecisionType.REQUESTED_CHANGES, provenance("jdoe")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, stateService, auditWriter, eventPublisher);
    }

    private DeploymentRequestEntity request(QueryStatus status) {
        var request = new DeploymentRequestEntity();
        request.setId(requestId);
        request.setOrganizationId(orgId);
        request.setPipelineId(pipelineId);
        request.setVersion("2.4.1");
        request.setStatus(status);
        return request;
    }

    private DeploymentTicketProvenance provenance(String actor) {
        return new DeploymentTicketProvenance("JIRA", "OPS-7", channelId, actor,
                "Jira ticket OPS-7 moved to 'Done'");
    }
}
