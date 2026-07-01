package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorGroupPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorGroupPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.core.api.UserGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The single point that unions a user's direct API-connector grant with every unexpired group grant
 * for a group they belong to (AF-530). The connector permission surface is scattered across several
 * services (submit-time enforcement, response-field masking, text-to-API access, admin visibility),
 * so every one of them routes through here to get the same most-permissive effective permission.
 */
@Service
@RequiredArgsConstructor
class EffectiveApiConnectorPermissionResolver {

    private final ApiConnectorUserPermissionRepository userPermissionRepository;
    private final ApiConnectorGroupPermissionRepository groupPermissionRepository;
    private final UserGroupService userGroupService;

    /** Merged effective permission on a connector, or empty when no unexpired grant applies. */
    @Transactional(readOnly = true)
    Optional<ResolvedApiConnectorPermission> resolve(UUID connectorId, UUID userId) {
        var now = Instant.now();
        var contributions = new ArrayList<Contribution>();
        userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId)
                .filter(p -> isActive(p.getExpiresAt(), now))
                .map(Contribution::from)
                .ifPresent(contributions::add);
        var groupIds = userGroupService.findGroupIdsForUser(userId);
        if (!groupIds.isEmpty()) {
            groupPermissionRepository.findByGroupIdIn(groupIds).stream()
                    .filter(p -> p.getConnectorId().equals(connectorId))
                    .filter(p -> isActive(p.getExpiresAt(), now))
                    .map(Contribution::from)
                    .forEach(contributions::add);
        }
        if (contributions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merge(connectorId, userId, contributions));
    }

    /** The connectors a user can reach — union of direct and group grants (unexpired). */
    @Transactional(readOnly = true)
    Set<UUID> connectorIdsFor(UUID userId) {
        var now = Instant.now();
        var ids = new LinkedHashSet<UUID>();
        userPermissionRepository.findByUserId(userId).stream()
                .filter(p -> isActive(p.getExpiresAt(), now))
                .forEach(p -> ids.add(p.getConnectorId()));
        var groupIds = userGroupService.findGroupIdsForUser(userId);
        if (!groupIds.isEmpty()) {
            groupPermissionRepository.findByGroupIdIn(groupIds).stream()
                    .filter(p -> isActive(p.getExpiresAt(), now))
                    .forEach(p -> ids.add(p.getConnectorId()));
        }
        return ids;
    }

    private static boolean isActive(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    private static ResolvedApiConnectorPermission merge(UUID connectorId, UUID userId,
                                                        List<Contribution> parts) {
        boolean canRead = false;
        boolean canWrite = false;
        boolean canBreakGlass = false;
        Instant expiresAt = parts.get(0).expiresAt();
        boolean anyNeverExpires = false;
        for (var p : parts) {
            canRead |= p.canRead();
            canWrite |= p.canWrite();
            canBreakGlass |= p.canBreakGlass();
            if (p.expiresAt() == null) {
                anyNeverExpires = true;
            } else if (expiresAt != null && p.expiresAt().isAfter(expiresAt)) {
                expiresAt = p.expiresAt();
            }
        }
        return new ResolvedApiConnectorPermission(
                connectorId,
                userId,
                canRead,
                canWrite,
                canBreakGlass,
                unionAllowList(parts),
                intersectRestriction(parts),
                anyNeverExpires ? null : expiresAt);
    }

    /** Allow-list union: an empty allow-list means "all operations", so it wins → empty. */
    private static List<String> unionAllowList(List<Contribution> parts) {
        var union = new LinkedHashSet<String>();
        for (var p : parts) {
            var values = p.allowedOperations();
            if (values == null || values.length == 0) {
                return List.of();
            }
            for (var v : values) {
                union.add(v);
            }
        }
        return List.copyOf(union);
    }

    /** Restriction intersection: a field is masked only when every contribution masks it. */
    private static List<String> intersectRestriction(List<Contribution> parts) {
        Set<String> intersection = null;
        for (var p : parts) {
            var values = p.restrictedResponseFields();
            if (values == null || values.length == 0) {
                return List.of();
            }
            var current = new LinkedHashSet<>(List.of(values));
            if (intersection == null) {
                intersection = current;
            } else {
                intersection.retainAll(current);
            }
            if (intersection.isEmpty()) {
                return List.of();
            }
        }
        return intersection == null ? List.of() : List.copyOf(intersection);
    }

    /**
     * Effective connector permission after the union. An empty {@code allowedOperations} means every
     * operation is allowed (least-restrictive); {@code restrictedResponseFields} is the set masked
     * for every contributing grant.
     */
    record ResolvedApiConnectorPermission(UUID connectorId, UUID userId, boolean canRead,
                                          boolean canWrite, boolean canBreakGlass,
                                          List<String> allowedOperations,
                                          List<String> restrictedResponseFields, Instant expiresAt) {
    }

    private record Contribution(boolean canRead, boolean canWrite, boolean canBreakGlass,
                                String[] allowedOperations, String[] restrictedResponseFields,
                                Instant expiresAt) {

        static Contribution from(ApiConnectorUserPermissionEntity e) {
            return new Contribution(e.isCanRead(), e.isCanWrite(), e.isCanBreakGlass(),
                    e.getAllowedOperations(), e.getRestrictedResponseFields(), e.getExpiresAt());
        }

        static Contribution from(ApiConnectorGroupPermissionEntity e) {
            return new Contribution(e.isCanRead(), e.isCanWrite(), e.isCanBreakGlass(),
                    e.getAllowedOperations(), e.getRestrictedResponseFields(), e.getExpiresAt());
        }
    }
}
