package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ItemDecisionOutcome;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultAttestationItemStateService implements AttestationItemStateService {

    private final AttestationItemRepository itemRepository;
    private final DatasourceAdminService datasourceAdminService;

    @Override
    @Transactional
    public ItemDecisionOutcome certify(UUID itemId, UUID reviewerId, String comment,
                                       AttestationItemCloseReason reason) {
        var item = lockOrThrow(itemId);
        if (item.getDecision() != AttestationItemDecision.PENDING) {
            return replay(item);
        }
        applyDecision(item, AttestationItemDecision.CERTIFIED, reviewerId, comment, reason);
        return new ItemDecisionOutcome(itemId, AttestationItemDecision.CERTIFIED, false);
    }

    @Override
    @Transactional
    public ItemDecisionOutcome revoke(UUID itemId, UUID reviewerId, String comment,
                                      AttestationItemCloseReason reason) {
        var item = lockOrThrow(itemId);
        if (item.getDecision() != AttestationItemDecision.PENDING) {
            return replay(item);
        }
        revokeUnderlyingPermission(item);
        applyDecision(item, AttestationItemDecision.REVOKED, reviewerId, comment, reason);
        return new ItemDecisionOutcome(itemId, AttestationItemDecision.REVOKED, false);
    }

    private void revokeUnderlyingPermission(AttestationItemEntity item) {
        try {
            datasourceAdminService.revokePermission(item.getDatasourceId(),
                    item.getOrganizationId(), item.getPermissionId());
        } catch (DatasourcePermissionNotFoundException ex) {
            // The grant was already revoked out-of-band (admin, JIT expiry, a prior partial run).
            // The intent — that access no longer exists — is met; record the REVOKED decision anyway.
            log.warn("Permission {} for attestation item {} already absent during revoke",
                    item.getPermissionId(), item.getId());
        }
    }

    private void applyDecision(AttestationItemEntity item, AttestationItemDecision decision,
                               UUID reviewerId, String comment, AttestationItemCloseReason reason) {
        item.setDecision(decision);
        item.setCloseReason(reason);
        item.setDecidedBy(reviewerId);
        item.setDecidedAt(Instant.now());
        item.setDecisionComment(comment);
        itemRepository.save(item);
    }

    private static ItemDecisionOutcome replay(AttestationItemEntity item) {
        return new ItemDecisionOutcome(item.getId(), item.getDecision(), true);
    }

    private AttestationItemEntity lockOrThrow(UUID itemId) {
        return itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new AttestationItemNotFoundException(itemId));
    }
}
