package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.serviceaccounts.api.GrantServiceAccountDelegationCommand;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationExistsException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationPrincipalInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountDelegatedPrincipalEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountDelegatedPrincipalRepository;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grants and revokes the delegated principals the on-behalf-of header is checked against (#874).
 * The service account must already be typed (a human id is a 404, never a way to make a person
 * "act for" another person); the principal must be an active human of the same organization —
 * the same shape as the owner check in {@code DefaultServiceAccountAdminService}.
 */
@Service
@RequiredArgsConstructor
class DefaultServiceAccountDelegationService implements ServiceAccountDelegationService {

    private final ServiceAccountDelegatedPrincipalRepository repository;
    private final ServiceAccountRepository serviceAccountRepository;
    private final UserAdminService userAdminService;
    private final Clock clock;

    @Override
    @Transactional
    public ServiceAccountDelegationView grant(UUID organizationId, UUID actorUserId,
                                              GrantServiceAccountDelegationCommand command) {
        var now = clock.instant();
        requireServiceAccount(organizationId, command.serviceAccountUserId());
        requireHumanPrincipal(organizationId, command.principalUserId());
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw new ServiceAccountDelegationInvalidException();
        }
        repository.findByServiceAccountUserIdAndPrincipalUserIdAndRevokedAtIsNull(
                        command.serviceAccountUserId(), command.principalUserId())
                .ifPresent(existing -> {
                    if (existing.isLiveAt(now)) {
                        throw new ServiceAccountDelegationExistsException(
                                command.serviceAccountUserId(), command.principalUserId());
                    }
                    // Expired but never revoked: it no longer authorises anything, yet it still
                    // holds the one live slot of the partial unique index. Retire it rather than
                    // answer a misleading 409 "already allowed".
                    existing.setRevokedAt(now);
                    existing.setRevokedBy(actorUserId);
                    repository.saveAndFlush(existing);
                });
        var entity = new ServiceAccountDelegatedPrincipalEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setServiceAccountUserId(command.serviceAccountUserId());
        entity.setPrincipalUserId(command.principalUserId());
        entity.setGrantedBy(actorUserId);
        entity.setCreatedAt(now);
        entity.setExpiresAt(command.expiresAt());
        return toView(flushOrConflict(entity), users(organizationId, List.of(entity)), now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceAccountDelegationView> listForServiceAccount(UUID organizationId, UUID serviceAccountUserId) {
        requireServiceAccount(organizationId, serviceAccountUserId);
        return toViews(organizationId, repository
                .findAllByServiceAccountUserIdAndOrganizationIdOrderByCreatedAtDesc(serviceAccountUserId, organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceAccountDelegationView> listForPrincipal(UUID organizationId, UUID principalUserId) {
        return toViews(organizationId, repository
                .findAllByPrincipalUserIdAndOrganizationIdOrderByCreatedAtDesc(principalUserId, organizationId));
    }

    @Override
    @Transactional
    public ServiceAccountDelegationView revoke(UUID organizationId, UUID actorUserId, UUID delegationId,
                                               UUID serviceAccountUserId, UUID principalUserId) {
        var entity = repository.findByIdAndOrganizationId(delegationId, organizationId)
                .filter(row -> serviceAccountUserId == null
                        || serviceAccountUserId.equals(row.getServiceAccountUserId()))
                .filter(row -> principalUserId == null || principalUserId.equals(row.getPrincipalUserId()))
                .orElseThrow(() -> new ServiceAccountDelegationNotFoundException(delegationId));
        var now = clock.instant();
        if (entity.getRevokedAt() == null) {
            entity.setRevokedAt(now);
            entity.setRevokedBy(actorUserId);
            entity = repository.save(entity);
        }
        return toView(entity, users(organizationId, List.of(entity)), now);
    }

    private void requireServiceAccount(UUID organizationId, UUID serviceAccountUserId) {
        serviceAccountRepository.findByUserIdAndOrganizationId(serviceAccountUserId, organizationId)
                .orElseThrow(() -> new ServiceAccountNotFoundException(serviceAccountUserId));
    }

    private void requireHumanPrincipal(UUID organizationId, UUID principalUserId) {
        var principal = principalUserId == null ? null
                : userAdminService.findByIds(organizationId, List.of(principalUserId)).get(principalUserId);
        if (principal == null || !principal.active() || principal.principalType() != PrincipalType.HUMAN) {
            throw new ServiceAccountDelegationPrincipalInvalidException(principalUserId);
        }
    }

    private ServiceAccountDelegatedPrincipalEntity flushOrConflict(ServiceAccountDelegatedPrincipalEntity entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent grant took the live slot between the pre-check and the flush; the
            // transaction is already rollback-only, so keep the 409 contract intact.
            throw new ServiceAccountDelegationExistsException(entity.getServiceAccountUserId(),
                    entity.getPrincipalUserId());
        }
    }

    private List<ServiceAccountDelegationView> toViews(UUID organizationId,
                                                       List<ServiceAccountDelegatedPrincipalEntity> rows) {
        var users = users(organizationId, rows);
        var now = clock.instant();
        return rows.stream().map(row -> toView(row, users, now)).toList();
    }

    private Map<UUID, UserView> users(UUID organizationId, List<ServiceAccountDelegatedPrincipalEntity> rows) {
        var ids = new HashSet<UUID>();
        for (var row : rows) {
            ids.add(row.getServiceAccountUserId());
            ids.add(row.getPrincipalUserId());
        }
        return ids.isEmpty() ? Map.of() : userAdminService.findByIds(organizationId, ids);
    }

    private static ServiceAccountDelegationView toView(ServiceAccountDelegatedPrincipalEntity row,
                                                       Map<UUID, UserView> users, Instant now) {
        var account = users.get(row.getServiceAccountUserId());
        var principal = users.get(row.getPrincipalUserId());
        return new ServiceAccountDelegationView(
                row.getId(),
                row.getOrganizationId(),
                row.getServiceAccountUserId(),
                account == null ? null : account.email(),
                row.getPrincipalUserId(),
                principal == null ? null : principal.email(),
                row.getGrantedBy(),
                row.getCreatedAt(),
                row.getExpiresAt(),
                row.getRevokedAt(),
                ServiceAccountDelegationView.statusAt(row.getRevokedAt(), row.getExpiresAt(), now));
    }
}
