package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestNotCancellableException;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.api.AccessRequestNotPendingException;
import com.bablsoft.accessflow.access.events.AccessGrantExpiredEvent;
import com.bablsoft.accessflow.access.events.AccessGrantRevokedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestStatusChangedEvent;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantDecisionEntity;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantDecisionRepository;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.bablsoft.accessflow.core.api.DecisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccessGrantRequestStateServiceTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessGrantDecisionRepository decisionRepository;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks DefaultAccessGrantRequestStateService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID permissionId = UUID.randomUUID();

    private AccessGrantRequestEntity entity(AccessGrantStatus status) {
        var e = new AccessGrantRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(organizationId);
        e.setRequesterId(requesterId);
        e.setDatasourceId(datasourceId);
        e.setStatus(status);
        e.setRequestedDuration("PT4H");
        return e;
    }

    @Test
    void recordApprovalThrowsWhenNotPending() {
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(java.util.Optional.of(entity(AccessGrantStatus.APPROVED)));
        var command = new RecordAccessApprovalCommand(requestId, reviewerId, 0, 1, true, null);
        assertThatThrownBy(() -> service.recordApprovalAndAdvance(command))
                .isInstanceOf(AccessRequestNotPendingException.class);
    }

    @Test
    void recordApprovalIsIdempotentOnExistingDecision() {
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(java.util.Optional.of(entity(AccessGrantStatus.PENDING)));
        var existing = new AccessGrantDecisionEntity();
        existing.setId(UUID.randomUUID());
        existing.setReviewerId(reviewerId);
        when(decisionRepository.findAllByAccessGrantRequest_IdAndStage(requestId, 0))
                .thenReturn(List.of(existing));
        var command = new RecordAccessApprovalCommand(requestId, reviewerId, 0, 1, true, null);

        var result = service.recordApprovalAndAdvance(command);

        assertThat(result.wasIdempotentReplay()).isTrue();
        assertThat(result.resultingStatus()).isEqualTo(AccessGrantStatus.PENDING);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void recordApprovalAdvancesToApprovedOnFinalStage() {
        var e = entity(AccessGrantStatus.PENDING);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));
        when(decisionRepository.findAllByAccessGrantRequest_IdAndStage(requestId, 0))
                .thenReturn(List.of())                       // no existing decision
                .thenReturn(List.of(approvedDecision()));    // after insert: one approval
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            var d = (AccessGrantDecisionEntity) inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        var command = new RecordAccessApprovalCommand(requestId, reviewerId, 0, 1, true, "ok");

        var result = service.recordApprovalAndAdvance(command);

        assertThat(result.resultingStatus()).isEqualTo(AccessGrantStatus.APPROVED);
        assertThat(result.wasIdempotentReplay()).isFalse();
        assertThat(e.getStatus()).isEqualTo(AccessGrantStatus.APPROVED);
        verify(eventPublisher).publishEvent(any(AccessRequestStatusChangedEvent.class));
    }

    @Test
    void recordApprovalStaysPendingBeforeFinalStage() {
        var e = entity(AccessGrantStatus.PENDING);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));
        when(decisionRepository.findAllByAccessGrantRequest_IdAndStage(requestId, 0))
                .thenReturn(List.of())
                .thenReturn(List.of(approvedDecision()));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // isLastStage=false → no transition even though threshold met
        var command = new RecordAccessApprovalCommand(requestId, reviewerId, 0, 1, false, null);

        var result = service.recordApprovalAndAdvance(command);

        assertThat(result.resultingStatus()).isEqualTo(AccessGrantStatus.PENDING);
        assertThat(e.getStatus()).isEqualTo(AccessGrantStatus.PENDING);
    }

    @Test
    void recordRejectionTransitionsToRejected() {
        var e = entity(AccessGrantStatus.PENDING);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));
        when(decisionRepository.findAllByAccessGrantRequest_IdAndStage(requestId, 0))
                .thenReturn(List.of());
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            var d = (AccessGrantDecisionEntity) inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        var result = service.recordRejection(requestId, reviewerId, 0, "no");

        assertThat(result.resultingStatus()).isEqualTo(AccessGrantStatus.REJECTED);
        assertThat(e.getStatus()).isEqualTo(AccessGrantStatus.REJECTED);
    }

    @Test
    void cancelThrowsWhenNotPending() {
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(java.util.Optional.of(entity(AccessGrantStatus.APPROVED)));
        assertThatThrownBy(() -> service.cancel(requestId))
                .isInstanceOf(AccessRequestNotCancellableException.class);
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        var e = entity(AccessGrantStatus.PENDING);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));
        service.cancel(requestId);
        assertThat(e.getStatus()).isEqualTo(AccessGrantStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(AccessRequestStatusChangedEvent.class));
    }

    @Test
    void lockOrThrowRaisesNotFound() {
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.cancel(requestId))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    void attachGrantStoresPermissionAndExpiry() {
        var e = entity(AccessGrantStatus.APPROVED);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));
        var expiresAt = Instant.now().plusSeconds(3600);
        service.attachGrant(requestId, permissionId, expiresAt);
        assertThat(e.getGrantedPermissionId()).isEqualTo(permissionId);
        assertThat(e.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void expireReturnsFalseWhenNotApproved() {
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(java.util.Optional.of(entity(AccessGrantStatus.PENDING)));
        assertThat(service.expire(requestId)).isFalse();
        verify(datasourceAdminService, never()).revokePermission(any(), any(), any());
    }

    @Test
    void expireRevokesPermissionAndPublishesExpiredEvent() {
        var e = entity(AccessGrantStatus.APPROVED);
        e.setGrantedPermissionId(permissionId);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));

        assertThat(service.expire(requestId)).isTrue();

        assertThat(e.getStatus()).isEqualTo(AccessGrantStatus.EXPIRED);
        verify(datasourceAdminService).revokePermission(datasourceId, organizationId, permissionId);
        verify(eventPublisher).publishEvent(any(AccessGrantExpiredEvent.class));
        verify(eventPublisher).publishEvent(any(AccessRequestStatusChangedEvent.class));
    }

    @Test
    void expireToleratesMissingPermission() {
        var e = entity(AccessGrantStatus.APPROVED);
        e.setGrantedPermissionId(permissionId);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));
        org.mockito.Mockito.doThrow(new DatasourcePermissionNotFoundException(permissionId))
                .when(datasourceAdminService).revokePermission(datasourceId, organizationId, permissionId);

        assertThat(service.expire(requestId)).isTrue();
        assertThat(e.getStatus()).isEqualTo(AccessGrantStatus.EXPIRED);
    }

    @Test
    void revokeTransitionsToRevokedWithActor() {
        var e = entity(AccessGrantStatus.APPROVED);
        e.setGrantedPermissionId(permissionId);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(java.util.Optional.of(e));
        var actor = UUID.randomUUID();

        assertThat(service.revoke(requestId, actor)).isTrue();

        assertThat(e.getStatus()).isEqualTo(AccessGrantStatus.REVOKED);
        var captor = ArgumentCaptor.forClass(AccessGrantRevokedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().revokedByUserId()).isEqualTo(actor);
    }

    @Test
    void revokeReturnsFalseWhenNotApproved() {
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(java.util.Optional.of(entity(AccessGrantStatus.REJECTED)));
        assertThat(service.revoke(requestId, UUID.randomUUID())).isFalse();
    }

    @Test
    void listDecisionsMapsSnapshots() {
        var d = approvedDecision();
        var req = entity(AccessGrantStatus.PENDING);
        d.setAccessGrantRequest(req);
        when(decisionRepository.findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(requestId))
                .thenReturn(List.of(d));
        var snapshots = service.listDecisions(requestId);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).decision()).isEqualTo(DecisionType.APPROVED);
    }

    private AccessGrantDecisionEntity approvedDecision() {
        var d = new AccessGrantDecisionEntity();
        d.setId(UUID.randomUUID());
        d.setReviewerId(reviewerId);
        d.setDecision(DecisionType.APPROVED);
        d.setStage(0);
        d.setDecidedAt(Instant.now());
        return d;
    }
}
