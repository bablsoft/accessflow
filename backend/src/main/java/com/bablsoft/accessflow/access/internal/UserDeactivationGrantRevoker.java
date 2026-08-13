package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.events.UserDeactivatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Revokes every APPROVED JIT access grant of a user the moment they are deactivated, so standing
 * database/API access disappears together with login. Reuses the ordinary revocation path
 * ({@link AccessGrantRequestStateService#revoke}), system-attributed ({@code revokedByUserId=null}).
 *
 * <p>Per-row failures are swallowed so one broken grant cannot block the rest of the fan-out;
 * {@code revoke} itself is idempotent (non-APPROVED rows are a no-op) and tolerates permissions
 * that were already removed out-of-band.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class UserDeactivationGrantRevoker {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantRequestStateService stateService;

    @ApplicationModuleListener
    void onUserDeactivated(UserDeactivatedEvent event) {
        var grantIds = requestRepository.findIdsByRequesterIdAndStatus(
                event.userId(), AccessGrantStatus.APPROVED);
        for (var grantId : grantIds) {
            try {
                stateService.revoke(grantId, null);
            } catch (RuntimeException ex) {
                log.error("Failed to revoke access grant {} for deactivated user {}",
                        grantId, event.userId(), ex);
            }
        }
        if (!grantIds.isEmpty()) {
            log.info("Revoked {} active access grant(s) for deactivated user {}",
                    grantIds.size(), event.userId());
        }
    }
}
