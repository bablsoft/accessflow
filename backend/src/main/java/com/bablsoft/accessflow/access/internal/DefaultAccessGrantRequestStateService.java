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
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.bablsoft.accessflow.core.api.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultAccessGrantRequestStateService implements AccessGrantRequestStateService {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantDecisionRepository decisionRepository;
    private final DatasourceAdminService datasourceAdminService;
    private final ApiConnectorAdminService apiConnectorAdminService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public RecordAccessDecisionResult recordApprovalAndAdvance(RecordAccessApprovalCommand command) {
        var entity = lockOrThrow(command.accessRequestId());
        requirePending(entity);
        var existing = findExistingDecision(command.accessRequestId(), command.reviewerId(),
                command.stage());
        if (existing != null) {
            return new RecordAccessDecisionResult(existing.getId(), entity.getStatus(), true);
        }
        var inserted = persistDecision(entity, command.reviewerId(), DecisionType.APPROVED,
                command.comment(), command.stage());
        var stageApproved = countApprovalsAtStage(command.accessRequestId(), command.stage());
        if (stageApproved >= command.minApprovalsRequired() && command.isLastStage()) {
            transition(entity, AccessGrantStatus.APPROVED);
            return new RecordAccessDecisionResult(inserted.getId(), AccessGrantStatus.APPROVED, false);
        }
        return new RecordAccessDecisionResult(inserted.getId(), AccessGrantStatus.PENDING, false);
    }

    @Override
    @Transactional
    public RecordAccessDecisionResult recordRejection(UUID accessRequestId, UUID reviewerId,
                                                      int stage, String comment) {
        var entity = lockOrThrow(accessRequestId);
        requirePending(entity);
        var existing = findExistingDecision(accessRequestId, reviewerId, stage);
        if (existing != null) {
            return new RecordAccessDecisionResult(existing.getId(), entity.getStatus(), true);
        }
        var inserted = persistDecision(entity, reviewerId, DecisionType.REJECTED, comment, stage);
        transition(entity, AccessGrantStatus.REJECTED);
        return new RecordAccessDecisionResult(inserted.getId(), AccessGrantStatus.REJECTED, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccessDecisionSnapshot> listDecisions(UUID accessRequestId) {
        return decisionRepository.findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(accessRequestId)
                .stream()
                .map(DefaultAccessGrantRequestStateService::toSnapshot)
                .toList();
    }

    @Override
    @Transactional
    public void attachGrant(UUID accessRequestId, UUID permissionId, Instant expiresAt) {
        var entity = lockOrThrow(accessRequestId);
        if (entity.getStatus() != AccessGrantStatus.APPROVED) {
            throw new AccessRequestNotPendingException(accessRequestId, entity.getStatus());
        }
        entity.setGrantedPermissionId(permissionId);
        entity.setExpiresAt(expiresAt);
        requestRepository.save(entity);
    }

    @Override
    @Transactional
    public void cancel(UUID accessRequestId) {
        var entity = lockOrThrow(accessRequestId);
        if (entity.getStatus() != AccessGrantStatus.PENDING) {
            throw new AccessRequestNotCancellableException(accessRequestId, entity.getStatus());
        }
        transition(entity, AccessGrantStatus.CANCELLED);
    }

    @Override
    @Transactional
    public boolean expire(UUID accessRequestId) {
        var entity = lockOrThrow(accessRequestId);
        if (entity.getStatus() != AccessGrantStatus.APPROVED) {
            return false;
        }
        var permissionId = entity.getGrantedPermissionId();
        revokeGrantedPermission(entity);
        transition(entity, AccessGrantStatus.EXPIRED);
        eventPublisher.publishEvent(new AccessGrantExpiredEvent(
                entity.getId(), entity.getRequesterId(), permissionId));
        return true;
    }

    @Override
    @Transactional
    public boolean revoke(UUID accessRequestId, UUID revokedByUserId) {
        var entity = lockOrThrow(accessRequestId);
        if (entity.getStatus() != AccessGrantStatus.APPROVED) {
            return false;
        }
        var permissionId = entity.getGrantedPermissionId();
        revokeGrantedPermission(entity);
        transition(entity, AccessGrantStatus.REVOKED);
        eventPublisher.publishEvent(new AccessGrantRevokedEvent(
                entity.getId(), entity.getRequesterId(), permissionId, revokedByUserId));
        return true;
    }

    private void revokeGrantedPermission(AccessGrantRequestEntity entity) {
        var permissionId = entity.getGrantedPermissionId();
        if (permissionId == null) {
            return;
        }
        try {
            if (entity.isConnectorRequest()) {
                apiConnectorAdminService.revokePermission(entity.getConnectorId(),
                        entity.getOrganizationId(), permissionId);
            } else {
                datasourceAdminService.revokePermission(entity.getDatasourceId(),
                        entity.getOrganizationId(), permissionId);
            }
        } catch (DatasourcePermissionNotFoundException | ApiConnectorPermissionNotFoundException
                 | ApiConnectorNotFoundException ex) {
            // Permission already gone (admin revoked it directly, a prior partial run, or the
            // connector itself was hard-deleted — cascading its permission rows away) — the grant
            // is effectively revoked; proceed with the status transition.
            log.warn("Granted permission {} for access request {} already absent during revoke",
                    permissionId, entity.getId());
        }
    }

    private void transition(AccessGrantRequestEntity entity, AccessGrantStatus next) {
        var previous = entity.getStatus();
        if (previous == next) {
            return;
        }
        entity.setStatus(next);
        requestRepository.save(entity);
        eventPublisher.publishEvent(new AccessRequestStatusChangedEvent(
                entity.getId(), entity.getRequesterId(), previous, next));
    }

    private AccessGrantRequestEntity lockOrThrow(UUID id) {
        return requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AccessRequestNotFoundException(id));
    }

    private static void requirePending(AccessGrantRequestEntity entity) {
        if (entity.getStatus() != AccessGrantStatus.PENDING) {
            throw new AccessRequestNotPendingException(entity.getId(), entity.getStatus());
        }
    }

    private AccessGrantDecisionEntity findExistingDecision(UUID accessRequestId, UUID reviewerId,
                                                           int stage) {
        return decisionRepository.findAllByAccessGrantRequest_IdAndStage(accessRequestId, stage)
                .stream()
                .filter(d -> d.getReviewerId().equals(reviewerId))
                .findFirst()
                .orElse(null);
    }

    private AccessGrantDecisionEntity persistDecision(AccessGrantRequestEntity request,
                                                      UUID reviewerId, DecisionType decision,
                                                      String comment, int stage) {
        var entity = new AccessGrantDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setAccessGrantRequest(request);
        entity.setReviewerId(reviewerId);
        entity.setDecision(decision);
        entity.setComment(comment);
        entity.setStage(stage);
        return decisionRepository.save(entity);
    }

    private int countApprovalsAtStage(UUID accessRequestId, int stage) {
        return (int) decisionRepository.findAllByAccessGrantRequest_IdAndStage(accessRequestId, stage)
                .stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVED)
                .count();
    }

    private static AccessDecisionSnapshot toSnapshot(AccessGrantDecisionEntity entity) {
        return new AccessDecisionSnapshot(
                entity.getId(),
                entity.getAccessGrantRequest().getId(),
                entity.getReviewerId(),
                entity.getDecision(),
                entity.getComment(),
                entity.getStage(),
                entity.getDecidedAt());
    }
}
