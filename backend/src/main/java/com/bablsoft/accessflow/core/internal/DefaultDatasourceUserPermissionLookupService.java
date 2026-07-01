package com.bablsoft.accessflow.core.internal;

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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDatasourceUserPermissionLookupService implements DatasourceUserPermissionLookupService {

    private final DatasourceUserPermissionRepository permissionRepository;
    private final DatasourceGroupPermissionRepository groupPermissionRepository;
    private final UserGroupMembershipRepository membershipRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<DatasourceUserPermissionView> findFor(UUID userId, UUID datasourceId) {
        var now = Instant.now();
        var contributions = new ArrayList<Contribution>();
        permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId)
                .filter(p -> isActive(p.getExpiresAt(), now))
                .map(Contribution::from)
                .ifPresent(contributions::add);
        var groupIds = membershipRepository.findGroupIdsForUser(userId);
        if (!groupIds.isEmpty()) {
            groupPermissionRepository.findAllByGroup_IdIn(groupIds).stream()
                    .filter(p -> p.getDatasource().getId().equals(datasourceId))
                    .filter(p -> isActive(p.getExpiresAt(), now))
                    .map(Contribution::from)
                    .forEach(contributions::add);
        }
        if (contributions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merge(userId, datasourceId, contributions));
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
        var byDatasource = new LinkedHashMap<UUID, List<Contribution>>();
        permissionRepository.findAllByUser_IdAndCanBreakGlassTrue(userId).stream()
                .filter(p -> isActive(p.getExpiresAt(), now))
                .forEach(p -> byDatasource
                        .computeIfAbsent(p.getDatasource().getId(), k -> new ArrayList<>())
                        .add(Contribution.from(p)));
        var groupIds = membershipRepository.findGroupIdsForUser(userId);
        if (!groupIds.isEmpty()) {
            groupPermissionRepository.findAllByGroup_IdInAndCanBreakGlassTrue(groupIds).stream()
                    .filter(p -> isActive(p.getExpiresAt(), now))
                    .forEach(p -> byDatasource
                            .computeIfAbsent(p.getDatasource().getId(), k -> new ArrayList<>())
                            .add(Contribution.from(p)));
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
                                                      List<Contribution> parts) {
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
                parts.get(0).id(),
                userId,
                datasourceId,
                canRead,
                canWrite,
                canDdl,
                canBreakGlass,
                unionAllowList(parts, Contribution::allowedSchemas),
                unionAllowList(parts, Contribution::allowedTables),
                intersectRestriction(parts),
                anyNeverExpires ? null : expiresAt);
    }

    /** Allow-list union: a null/empty contribution means "all allowed", so it wins → empty list. */
    private static List<String> unionAllowList(List<Contribution> parts,
                                               java.util.function.Function<Contribution, String[]> field) {
        var union = new LinkedHashSet<String>();
        for (var p : parts) {
            var values = field.apply(p);
            if (values == null || values.length == 0) {
                return List.of();
            }
            for (var v : values) {
                union.add(v);
            }
        }
        return List.copyOf(union);
    }

    /** Restriction intersection: a column is masked only when every contribution masks it. */
    private static List<String> intersectRestriction(List<Contribution> parts) {
        Set<String> intersection = null;
        for (var p : parts) {
            var values = p.restrictedColumns();
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

    private static List<String> toList(String[] array) {
        return array == null ? List.of() : List.of(array);
    }

    private record Contribution(UUID id, boolean canRead, boolean canWrite, boolean canDdl,
                                boolean canBreakGlass, String[] allowedSchemas, String[] allowedTables,
                                String[] restrictedColumns, Instant expiresAt) {

        static Contribution from(DatasourceUserPermissionEntity e) {
            return new Contribution(e.getId(), e.isCanRead(), e.isCanWrite(), e.isCanDdl(),
                    e.isCanBreakGlass(), e.getAllowedSchemas(), e.getAllowedTables(),
                    e.getRestrictedColumns(), e.getExpiresAt());
        }

        static Contribution from(DatasourceGroupPermissionEntity e) {
            return new Contribution(e.getId(), e.isCanRead(), e.isCanWrite(), e.isCanDdl(),
                    e.isCanBreakGlass(), e.getAllowedSchemas(), e.getAllowedTables(),
                    e.getRestrictedColumns(), e.getExpiresAt());
        }
    }
}
