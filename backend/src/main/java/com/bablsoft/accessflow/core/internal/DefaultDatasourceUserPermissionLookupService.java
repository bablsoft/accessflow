package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceGroupPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
class DefaultDatasourceUserPermissionLookupService implements DatasourceUserPermissionLookupService {

    private final DatasourceUserPermissionRepository permissionRepository;
    private final DatasourceGroupPermissionRepository groupPermissionRepository;
    private final UserGroupMembershipRepository membershipRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<DatasourceUserPermissionView> findFor(UUID userId, UUID datasourceId) {
        var contributions = findContributions(userId, datasourceId);
        if (contributions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merge(userId, datasourceId, contributions));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourcePermissionContribution> findContributions(UUID userId, UUID datasourceId) {
        var now = Instant.now();
        var contributions = new ArrayList<DatasourcePermissionContribution>();
        permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId)
                .filter(p -> isActive(p.getExpiresAt(), now))
                .map(DefaultDatasourceUserPermissionLookupService::toContribution)
                .ifPresent(contributions::add);
        var groupIds = membershipRepository.findGroupIdsForUser(userId);
        if (!groupIds.isEmpty()) {
            groupPermissionRepository.findAllByGroup_IdIn(groupIds).stream()
                    .filter(p -> p.getDatasource().getId().equals(datasourceId))
                    .filter(p -> isActive(p.getExpiresAt(), now))
                    .map(p -> toContribution(p, userId))
                    .forEach(contributions::add);
        }
        return contributions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourcePermissionContribution> findContributionsForDatasource(UUID datasourceId) {
        var now = Instant.now();
        var contributions = new ArrayList<DatasourcePermissionContribution>();
        permissionRepository.findAllByDatasource_Id(datasourceId).stream()
                .filter(p -> isActive(p.getExpiresAt(), now))
                .map(DefaultDatasourceUserPermissionLookupService::toContribution)
                .forEach(contributions::add);
        var groupPermissions = groupPermissionRepository.findAllByDatasource_Id(datasourceId).stream()
                .filter(p -> isActive(p.getExpiresAt(), now))
                .toList();
        expandGroups(groupPermissions, contributions);
        return contributions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourcePermissionContribution> findBreakGlassContributionsForOrganization(
            UUID organizationId) {
        var now = Instant.now();
        var contributions = new ArrayList<DatasourcePermissionContribution>();
        permissionRepository.findAllByDatasource_Organization_IdAndCanBreakGlassTrue(organizationId)
                .stream()
                .filter(p -> isActive(p.getExpiresAt(), now))
                .map(DefaultDatasourceUserPermissionLookupService::toContribution)
                .forEach(contributions::add);
        var groupPermissions = groupPermissionRepository
                .findAllByOrganizationIdAndCanBreakGlassTrue(organizationId).stream()
                .filter(p -> isActive(p.getExpiresAt(), now))
                .toList();
        expandGroups(groupPermissions, contributions);
        return contributions;
    }

    /**
     * Expand each group grant into one contribution per member. The merge is per-user, so a group
     * grant has to be expanded before it can be merged with that member's own direct row. One
     * membership query for all groups, not one per grant.
     */
    private void expandGroups(List<DatasourceGroupPermissionEntity> groupPermissions,
                              List<DatasourcePermissionContribution> into) {
        if (groupPermissions.isEmpty()) {
            return;
        }
        var membersByGroup = new LinkedHashMap<UUID, List<UUID>>();
        var groupIds = groupPermissions.stream()
                .map(p -> p.getGroup().getId())
                .distinct()
                .toList();
        for (var membership : membershipRepository.findAllByGroup_IdIn(groupIds)) {
            membersByGroup.computeIfAbsent(membership.getGroup().getId(), k -> new ArrayList<>())
                    .add(membership.getUser().getId());
        }
        for (var groupPermission : groupPermissions) {
            for (var memberId : membersByGroup.getOrDefault(groupPermission.getGroup().getId(),
                    List.of())) {
                into.add(toContribution(groupPermission, memberId));
            }
        }
    }

    @Override
    public Optional<DatasourceUserPermissionView> mergeContributions(
            List<DatasourcePermissionContribution> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            return Optional.empty();
        }
        var first = contributions.get(0);
        return Optional.of(merge(first.userId(), first.datasourceId(), contributions));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DatasourceUserPermissionView> findDirectFor(UUID userId, UUID datasourceId) {
        return permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId)
                .map(DefaultDatasourceUserPermissionLookupService::toDirectView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourceUserPermissionView> findBreakGlassEligible(UUID userId) {
        var now = Instant.now();
        // One bucket of contributions per datasource, merged into a single effective view.
        var byDatasource = new LinkedHashMap<UUID, List<DatasourcePermissionContribution>>();
        permissionRepository.findAllByUser_IdAndCanBreakGlassTrue(userId).stream()
                .filter(p -> isActive(p.getExpiresAt(), now))
                .forEach(p -> byDatasource
                        .computeIfAbsent(p.getDatasource().getId(), k -> new ArrayList<>())
                        .add(toContribution(p)));
        var groupIds = membershipRepository.findGroupIdsForUser(userId);
        if (!groupIds.isEmpty()) {
            groupPermissionRepository.findAllByGroup_IdInAndCanBreakGlassTrue(groupIds).stream()
                    .filter(p -> isActive(p.getExpiresAt(), now))
                    .forEach(p -> byDatasource
                            .computeIfAbsent(p.getDatasource().getId(), k -> new ArrayList<>())
                            .add(toContribution(p, userId)));
        }
        return byDatasource.entrySet().stream()
                .map(e -> merge(userId, e.getKey(), e.getValue()))
                .toList();
    }

    private static boolean isActive(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    /**
     * Merge one datasource's contributing grants into a single effective view. Boolean flags OR;
     * allow-lists union (null wins = all allowed); restricted-columns intersect (empty wins =
     * nothing masked); expiry is the latest among contributors (null wins = never expires).
     */
    private static DatasourceUserPermissionView merge(UUID userId, UUID datasourceId,
                                                      List<DatasourcePermissionContribution> parts) {
        boolean canRead = false;
        boolean canWrite = false;
        boolean canDdl = false;
        boolean canBreakGlass = false;
        Instant expiresAt = parts.get(0).expiresAt();
        boolean anyNeverExpires = false;
        for (var p : parts) {
            canRead |= p.canRead();
            canWrite |= p.canWrite();
            canDdl |= p.canDdl();
            canBreakGlass |= p.canBreakGlass();
            if (p.expiresAt() == null) {
                anyNeverExpires = true;
            } else if (expiresAt != null && p.expiresAt().isAfter(expiresAt)) {
                expiresAt = p.expiresAt();
            }
        }
        return new DatasourceUserPermissionView(
                parts.get(0).sourceId(),
                userId,
                datasourceId,
                canRead,
                canWrite,
                canDdl,
                canBreakGlass,
                unionAllowList(parts, DatasourcePermissionContribution::allowedSchemas),
                unionAllowList(parts, DatasourcePermissionContribution::allowedTables),
                intersectRestriction(parts),
                anyNeverExpires ? null : expiresAt);
    }

    /** Allow-list union: a null/empty contribution means "all allowed", so it wins → empty list. */
    private static List<String> unionAllowList(
            List<DatasourcePermissionContribution> parts,
            Function<DatasourcePermissionContribution, List<String>> field) {
        var union = new LinkedHashSet<String>();
        for (var p : parts) {
            var values = field.apply(p);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            union.addAll(values);
        }
        return List.copyOf(union);
    }

    /** Restriction intersection: a column is masked only when every contribution masks it. */
    private static List<String> intersectRestriction(
            List<DatasourcePermissionContribution> parts) {
        Set<String> intersection = null;
        for (var p : parts) {
            var values = p.restrictedColumns();
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            var current = new LinkedHashSet<>(values);
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

    private static DatasourceUserPermissionView toDirectView(DatasourceUserPermissionEntity entity) {
        return new DatasourceUserPermissionView(
                entity.getId(),
                entity.getUser().getId(),
                entity.getDatasource().getId(),
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                entity.isCanBreakGlass(),
                toList(entity.getAllowedSchemas()),
                toList(entity.getAllowedTables()),
                toList(entity.getRestrictedColumns()),
                entity.getExpiresAt());
    }

    private static DatasourcePermissionContribution toContribution(
            DatasourceUserPermissionEntity e) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.DIRECT,
                e.getId(), e.getUser().getId(), e.getDatasource().getId(), null, null,
                e.isCanRead(), e.isCanWrite(), e.isCanDdl(), e.isCanBreakGlass(),
                toList(e.getAllowedSchemas()), toList(e.getAllowedTables()),
                toList(e.getRestrictedColumns()), e.getExpiresAt());
    }

    private static DatasourcePermissionContribution toContribution(
            DatasourceGroupPermissionEntity e, UUID userId) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.GROUP,
                e.getId(), userId, e.getDatasource().getId(), e.getGroup().getId(),
                e.getGroup().getName(), e.isCanRead(), e.isCanWrite(), e.isCanDdl(),
                e.isCanBreakGlass(), toList(e.getAllowedSchemas()), toList(e.getAllowedTables()),
                toList(e.getRestrictedColumns()), e.getExpiresAt());
    }

    private static List<String> toList(String[] array) {
        return array == null ? List.of() : List.of(array);
    }
}
