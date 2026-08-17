package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DelegatedIdentity;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.ReviewDelegationLookupService;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDelegationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDelegationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves active review delegations (#622).
 *
 * <p><strong>Deliberately non-transitive.</strong> This service reads the delegation table exactly
 * once and never follows a delegator's own delegations, so A→B→C confers nothing on C. Enforcing it
 * here rather than on write is what makes it hold: a creation-time check is defeated simply by
 * creating the two delegations in the other order. Transitivity would also make
 * {@code on_behalf_of_user_id} a path rather than an id, which the audit trail cannot express, and
 * would leave a cycle A→B→A with no answer to "is the delegator the submitter?".
 *
 * <p>The delegator's role name is read live from the user row rather than snapshotted onto the
 * delegation, so losing a role immediately narrows what a delegate can do on their behalf.
 */
@Service
@RequiredArgsConstructor
public class DefaultReviewDelegationLookupService implements ReviewDelegationLookupService {

    private final ReviewDelegationRepository delegationRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<DelegatedIdentity> findActiveForDelegate(UUID organizationId, UUID actingUserId,
                                                         DelegationScopeKind resourceKind,
                                                         UUID resourceId) {
        var rows = delegationRepository.findActiveForDelegate(organizationId, actingUserId,
                clock.instant());
        if (rows.isEmpty()) {
            return List.of();
        }
        var scoped = resourceKind == null
                ? rows
                : rows.stream().filter(row -> coversResource(row, resourceKind, resourceId)).toList();
        if (scoped.isEmpty()) {
            return List.of();
        }
        // One batch lookup for the whole set — the alternative is a user query per delegation on
        // every queue render and every decision.
        var roleNames = roleNamesById(scoped);
        return scoped.stream()
                .map(row -> new DelegatedIdentity(row.getId(), row.getDelegatorId(),
                        roleNames.get(row.getDelegatorId()), row.getScopeKind(), row.getScopeId()))
                .toList();
    }


    /**
     * A scoped delegation matches only its own resource; an unrestricted one matches everything.
     * A null {@code resourceId} never satisfies a scoped delegation, so a request carrying no
     * resource of this kind fails closed.
     */
    private static boolean coversResource(ReviewDelegationEntity row, DelegationScopeKind kind,
                                          UUID resourceId) {
        if (row.getScopeKind() == null) {
            return true;
        }
        return row.getScopeKind() == kind && resourceId != null && resourceId.equals(row.getScopeId());
    }

    private Map<UUID, String> roleNamesById(List<ReviewDelegationEntity> rows) {
        var delegatorIds = rows.stream().map(ReviewDelegationEntity::getDelegatorId).distinct().toList();
        return userRepository.findAllById(delegatorIds).stream()
                .filter(user -> user.roleName() != null)
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::roleName,
                        (first, second) -> first));
    }
}
