package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DeniedColumns;
import com.bablsoft.accessflow.core.api.DeniedShapes;
import com.bablsoft.accessflow.core.api.DeniedTables;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.proxy.api.RowCapResolver;
import com.bablsoft.accessflow.proxy.api.RowCapSource;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.AttributedValue;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.BytesScannedLimit;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.Capability;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.CapabilityKind;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.Grant;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.RowCap;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.Scope;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplorerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Assembles the explorer view from the enforcement services (#946). The merged values come only
 * from {@link DatasourceUserPermissionLookupService#mergeContributions}; this class merely
 * attributes each merged element to the contributions that carry it, and the row cap comes from
 * the same {@link RowCapResolver} clamp the executor applies.
 */
@Service
@RequiredArgsConstructor
class DefaultEffectivePermissionExplorerService implements EffectivePermissionExplorerService {

    private final UserQueryService userQueryService;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final RolePermissionHolderLookupService rolePermissionHolderLookupService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final RowLimitPolicyResolutionService rowLimitPolicyResolutionService;
    private final UserGroupService userGroupService;
    private final RowCapResolver rowCapResolver;

    // Not @Transactional, like the access simulator: independent transactional reads, and a handled
    // not-found from an inner call must not mark an outer transaction rollback-only.
    @Override
    public EffectivePermissionExplanation explain(UUID organizationId, UUID userId,
                                                  UUID datasourceId) {
        var user = userQueryService.findById(userId)
                .filter(u -> organizationId.equals(u.organizationId()))
                .orElseThrow(() -> new UserNotFoundException(userId));
        var datasource = datasourceAdminService.getForAdmin(datasourceId, organizationId);

        var contributions = permissionLookupService.findContributions(userId, datasourceId);
        var merged = permissionLookupService.mergeContributions(contributions).orElse(null);
        boolean queryAdmin = rolePermissionHolderLookupService
                .findUserIdsWithPermission(organizationId, Permission.QUERY_ADMIN)
                .contains(userId);

        var masking = maskingPolicyResolutionService.explain(organizationId, datasourceId, userId);
        var groupNames = userGroupService.listAll(organizationId).stream()
                .collect(Collectors.toMap(UserGroupView::id, UserGroupView::name, (a, b) -> a));

        return new EffectivePermissionExplanation(
                user.id(), user.email(), user.displayName(),
                datasource.id(), datasource.name(), datasource.dbType(),
                merged != null, queryAdmin,
                merged != null ? merged.expiresAt() : null,
                contributions.stream().map(DefaultEffectivePermissionExplorerService::toGrant).toList(),
                capabilities(merged, contributions),
                scope(merged, contributions, DatasourceUserPermissionView::allowedSchemas,
                        DatasourcePermissionContribution::allowedSchemas),
                scope(merged, contributions, DatasourceUserPermissionView::allowedTables,
                        DatasourcePermissionContribution::allowedTables),
                // An intersection: every contribution restricts each surviving column.
                attributeToAll(merged == null ? List.of() : merged.restrictedColumns(),
                        contributions),
                attribute(merged == null ? List.of() : merged.deniedColumns(), contributions,
                        c -> DeniedColumns.normalize(c.deniedColumns())),
                attribute(merged == null ? List.of() : merged.deniedSchemas(), contributions,
                        c -> DeniedTables.normalize(c.deniedSchemas())),
                attribute(merged == null ? List.of() : merged.deniedTables(), contributions,
                        c -> DeniedTables.normalize(c.deniedTables())),
                deniedShapes(merged, contributions),
                rowCap(merged, contributions, datasource.maxRowsPerQuery()),
                bytesScannedLimit(merged, contributions),
                rowLimitPolicyResolutionService.findApplicable(organizationId, datasourceId, userId),
                masking.applied(),
                masking.revealed(),
                rowSecurityResolutionService.explainApplicable(organizationId, datasourceId, userId),
                groupNames);
    }

    private static Grant toGrant(DatasourcePermissionContribution c) {
        return new Grant(c.sourceId(), c.sourceKind(), c.groupId(), c.groupName(), c.expiresAt(),
                c.rowLimitOverride(), c.bytesScannedLimitOverride(), c.accessGrantRequestId());
    }

    private static List<Capability> capabilities(DatasourceUserPermissionView merged,
                                                 List<DatasourcePermissionContribution> parts) {
        return List.of(
                capability(CapabilityKind.READ, merged != null && merged.canRead(), parts,
                        DatasourcePermissionContribution::canRead),
                capability(CapabilityKind.WRITE, merged != null && merged.canWrite(), parts,
                        DatasourcePermissionContribution::canWrite),
                capability(CapabilityKind.DDL, merged != null && merged.canDdl(), parts,
                        DatasourcePermissionContribution::canDdl),
                capability(CapabilityKind.BREAK_GLASS, merged != null && merged.canBreakGlass(),
                        parts, DatasourcePermissionContribution::canBreakGlass));
    }

    private static Capability capability(CapabilityKind kind, boolean granted,
                                         List<DatasourcePermissionContribution> parts,
                                         Predicate<DatasourcePermissionContribution> flag) {
        return new Capability(kind, granted, granted ? idsWhere(parts, flag) : List.of());
    }

    private static Scope scope(DatasourceUserPermissionView merged,
                               List<DatasourcePermissionContribution> parts,
                               Function<DatasourceUserPermissionView, List<String>> mergedField,
                               Function<DatasourcePermissionContribution, List<String>> field) {
        if (merged == null) {
            return new Scope(false, List.of());
        }
        var values = mergedField.apply(merged);
        if (values.isEmpty()) {
            // The allow-list merge returns empty when any grant leaves it open.
            return new Scope(true, List.of());
        }
        return new Scope(false, attribute(values, parts, field));
    }

    private static List<AttributedValue> attribute(
            List<String> mergedValues, List<DatasourcePermissionContribution> parts,
            Function<DatasourcePermissionContribution, List<String>> field) {
        return mergedValues.stream()
                .map(value -> new AttributedValue(value,
                        idsWhere(parts, c -> containsIgnoreCase(field.apply(c), value))))
                .toList();
    }

    private static List<AttributedValue> attributeToAll(
            List<String> mergedValues, List<DatasourcePermissionContribution> parts) {
        return mergedValues.stream()
                .map(value -> new AttributedValue(value, idsWhere(parts, c -> true)))
                .toList();
    }

    private static List<AttributedValue> deniedShapes(DatasourceUserPermissionView merged,
                                                      List<DatasourcePermissionContribution> parts) {
        if (merged == null) {
            return List.of();
        }
        return merged.deniedShapes().stream()
                .map(shape -> new AttributedValue(shape.name(), idsWhere(parts,
                        c -> DeniedShapes.normalize(c.deniedShapes()).contains(shape))))
                .toList();
    }

    private RowCap rowCap(DatasourceUserPermissionView merged,
                          List<DatasourcePermissionContribution> parts, int datasourceCap) {
        var override = merged == null ? null : merged.rowLimitOverride();
        var cap = rowCapResolver.resolve(override, datasourceCap);
        var grantIds = cap.source() == RowCapSource.OVERRIDE
                ? idsWhere(parts, c -> Objects.equals(c.rowLimitOverride(), override))
                : List.<UUID>of();
        return new RowCap(cap.value(), cap.source(), cap.override(), cap.datasourceCap(),
                cap.globalCeiling(), grantIds);
    }

    private static BytesScannedLimit bytesScannedLimit(
            DatasourceUserPermissionView merged, List<DatasourcePermissionContribution> parts) {
        if (merged == null || merged.bytesScannedLimitOverride() == null) {
            return null;
        }
        var limit = merged.bytesScannedLimitOverride();
        return new BytesScannedLimit(limit,
                idsWhere(parts, c -> limit.equals(c.bytesScannedLimitOverride())));
    }

    private static List<UUID> idsWhere(List<DatasourcePermissionContribution> parts,
                                       Predicate<DatasourcePermissionContribution> predicate) {
        return parts.stream().filter(predicate).map(DatasourcePermissionContribution::sourceId)
                .toList();
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values != null && values.stream().anyMatch(v -> v.equalsIgnoreCase(value));
    }
}
