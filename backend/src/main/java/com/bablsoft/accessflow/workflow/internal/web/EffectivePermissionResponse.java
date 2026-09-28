package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.AccessTargetMatch;
import com.bablsoft.accessflow.core.api.AccessTargetMatchKind;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.proxy.api.RowCapSource;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.CapabilityKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record EffectivePermissionResponse(
        UserRef user,
        DatasourceRef datasource,
        boolean hasGrant,
        boolean queryAdmin,
        Instant expiresAt,
        List<GrantResponse> grants,
        List<CapabilityResponse> capabilities,
        ScopeResponse allowedSchemas,
        ScopeResponse allowedTables,
        List<AttributedValueResponse> restrictedColumns,
        List<AttributedValueResponse> deniedColumns,
        List<AttributedValueResponse> deniedSchemas,
        List<AttributedValueResponse> deniedTables,
        List<AttributedValueResponse> deniedShapes,
        RowCapResponse rowCap,
        BytesScannedLimitResponse bytesScannedLimit,
        List<TableRowLimitResponse> tableRowLimits,
        List<MaskedColumnResponse> maskedColumns,
        List<RevealedMaskResponse> revealedMasks,
        List<RowSecurityResponse> rowSecurity,
        List<RetentionMaskResponse> retentionMasks,
        List<SoftDeleteFilterResponse> softDeleteFilters) {

    record UserRef(UUID id, String email, String displayName) {
    }

    record DatasourceRef(UUID id, String name, DbType dbType) {
    }

    record GrantResponse(UUID grantId, DatasourcePermissionSourceKind sourceKind, UUID groupId,
                         String groupName, Instant expiresAt, Integer rowLimitOverride,
                         Long bytesScannedLimitOverride, UUID accessGrantRequestId) {
    }

    record CapabilityResponse(CapabilityKind capability, boolean granted, List<UUID> grantIds) {
    }

    record AttributedValueResponse(String value, List<UUID> grantIds) {
    }

    record ScopeResponse(boolean unrestricted, List<AttributedValueResponse> entries) {
    }

    record RowCapResponse(int value, RowCapSource source, Integer override, int datasourceCap,
                          int globalCeiling, List<UUID> grantIds) {
    }

    record BytesScannedLimitResponse(long value, List<UUID> grantIds) {
    }

    record TargetMatchResponse(AccessTargetMatchKind kind, String ref, String name) {
    }

    record TableRowLimitResponse(UUID policyId, String schemaName, String tableName, int maxRows,
                                 List<TargetMatchResponse> matchedBy) {
    }

    record MaskedColumnResponse(UUID policyId, String columnRef, MaskingStrategy strategy,
                                Map<String, String> params) {
    }

    record RevealedMaskResponse(UUID policyId, String columnRef, MaskingStrategy strategy,
                                List<TargetMatchResponse> revealedBy) {
    }

    record RowSecurityResponse(UUID policyId, String tableRef, String columnName,
                               RowSecurityOperator operator, List<Object> values,
                               RowSecurityValueType valueType, String valueExpression,
                               List<TargetMatchResponse> matchedBy) {
    }

    record RetentionMaskResponse(UUID policyId, String columnRef, MaskingStrategy strategy) {
    }

    record SoftDeleteFilterResponse(UUID policyId, String tableRef, String columnName) {
    }

    static EffectivePermissionResponse from(EffectivePermissionExplanation e) {
        var groupNames = e.groupNames();
        return new EffectivePermissionResponse(
                new UserRef(e.userId(), e.userEmail(), e.userDisplayName()),
                new DatasourceRef(e.datasourceId(), e.datasourceName(), e.dbType()),
                e.hasGrant(), e.queryAdmin(), e.expiresAt(),
                e.grants().stream().map(g -> new GrantResponse(g.grantId(), g.sourceKind(),
                        g.groupId(), g.groupName(), g.expiresAt(), g.rowLimitOverride(),
                        g.bytesScannedLimitOverride(), g.accessGrantRequestId())).toList(),
                e.capabilities().stream().map(c -> new CapabilityResponse(c.capability(),
                        c.granted(), c.grantIds())).toList(),
                scope(e.allowedSchemas()),
                scope(e.allowedTables()),
                values(e.restrictedColumns()),
                values(e.deniedColumns()),
                values(e.deniedSchemas()),
                values(e.deniedTables()),
                values(e.deniedShapes()),
                new RowCapResponse(e.rowCap().value(), e.rowCap().source(), e.rowCap().override(),
                        e.rowCap().datasourceCap(), e.rowCap().globalCeiling(),
                        e.rowCap().grantIds()),
                e.bytesScannedLimit() == null ? null : new BytesScannedLimitResponse(
                        e.bytesScannedLimit().value(), e.bytesScannedLimit().grantIds()),
                e.tableRowLimits().stream().map(p -> new TableRowLimitResponse(p.policyId(),
                        p.schemaName(), p.tableName(), p.maxRows(),
                        matches(p.matchedBy(), groupNames))).toList(),
                e.maskedColumns().stream().map(m -> new MaskedColumnResponse(m.policyId(),
                        m.columnRef(), m.strategy(), m.params())).toList(),
                e.revealedMasks().stream().map(m -> new RevealedMaskResponse(m.policyId(),
                        m.columnRef(), m.strategy(), matches(m.revealedBy(), groupNames))).toList(),
                e.rowSecurity().stream().map(r -> new RowSecurityResponse(
                        r.predicate().policyId(), r.predicate().tableRef(),
                        r.predicate().columnName(), r.predicate().operator(),
                        r.predicate().values(), r.valueType(), r.valueExpression(),
                        matches(r.matchedBy(), groupNames))).toList(),
                e.retentionMasks().stream().map(m -> new RetentionMaskResponse(m.policyId(),
                        m.columnRef(), m.strategy())).toList(),
                e.softDeleteFilters().stream().map(f -> new SoftDeleteFilterResponse(f.policyId(),
                        f.tableRef(), f.columnName())).toList());
    }

    private static ScopeResponse scope(EffectivePermissionExplanation.Scope scope) {
        return new ScopeResponse(scope.unrestricted(), values(scope.entries()));
    }

    private static List<AttributedValueResponse> values(
            List<EffectivePermissionExplanation.AttributedValue> values) {
        return values.stream().map(v -> new AttributedValueResponse(v.value(), v.grantIds()))
                .toList();
    }

    private static List<TargetMatchResponse> matches(List<AccessTargetMatch> matches,
                                                     Map<UUID, String> groupNames) {
        return matches.stream().map(m -> new TargetMatchResponse(m.kind(), m.ref(),
                m.kind() == AccessTargetMatchKind.GROUP ? groupName(m.ref(), groupNames) : null))
                .toList();
    }

    private static String groupName(String ref, Map<UUID, String> groupNames) {
        try {
            return groupNames.get(UUID.fromString(ref));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
