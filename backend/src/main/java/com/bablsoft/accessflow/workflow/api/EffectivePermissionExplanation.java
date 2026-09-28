package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.ApplicableRowLimitPolicy;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.ExplainedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.RevealedColumnMask;
import com.bablsoft.accessflow.proxy.api.RowCapSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The effective access of one user on one datasource (#946). Every attributed element names the
 * {@link Grant#grantId() grant ids} it came from; {@code grants} lists each active contribution
 * once. {@code groupNames} resolves the group ids a policy's {@code matched_by} may name.
 *
 * @param hasGrant   at least one active direct or group grant contributes
 * @param queryAdmin the user holds {@code QUERY_ADMIN}, which bypasses the per-datasource grant
 *                   gate entirely — capabilities and scopes below then describe grants that are
 *                   not consulted
 * @param expiresAt  when the merged access lapses; {@code null} when it never does or there is none
 */
public record EffectivePermissionExplanation(
        UUID userId,
        String userEmail,
        String userDisplayName,
        UUID datasourceId,
        String datasourceName,
        DbType dbType,
        boolean hasGrant,
        boolean queryAdmin,
        Instant expiresAt,
        List<Grant> grants,
        List<Capability> capabilities,
        Scope allowedSchemas,
        Scope allowedTables,
        List<AttributedValue> restrictedColumns,
        List<AttributedValue> deniedColumns,
        List<AttributedValue> deniedSchemas,
        List<AttributedValue> deniedTables,
        List<AttributedValue> deniedShapes,
        RowCap rowCap,
        BytesScannedLimit bytesScannedLimit,
        List<ApplicableRowLimitPolicy> tableRowLimits,
        List<ResolvedColumnMask> maskedColumns,
        List<RevealedColumnMask> revealedMasks,
        List<ExplainedRowSecurityPredicate> rowSecurity,
        Map<UUID, String> groupNames) {

    public enum CapabilityKind { READ, WRITE, DDL, BREAK_GLASS }

    /** One active direct or group grant contributing to the merge. */
    public record Grant(UUID grantId, DatasourcePermissionSourceKind sourceKind, UUID groupId,
                        String groupName, Instant expiresAt, Integer rowLimitOverride,
                        Long bytesScannedLimitOverride, UUID accessGrantRequestId) {
    }

    public record Capability(CapabilityKind capability, boolean granted, List<UUID> grantIds) {
        public Capability {
            grantIds = List.copyOf(grantIds);
        }
    }

    public record AttributedValue(String value, List<UUID> grantIds) {
        public AttributedValue {
            grantIds = List.copyOf(grantIds);
        }
    }

    /** An allow-list: {@code unrestricted} when the merged list is empty (everything allowed). */
    public record Scope(boolean unrestricted, List<AttributedValue> entries) {
        public Scope {
            entries = List.copyOf(entries);
        }
    }

    /**
     * The row cap the proxy enforces for a query touching no row-limit-policy table, and the bound
     * that set it. {@code grantIds} are the grants carrying the winning override, and only when
     * {@code source} is {@link RowCapSource#OVERRIDE}.
     */
    public record RowCap(int value, RowCapSource source, Integer override, int datasourceCap,
                         int globalCeiling, List<UUID> grantIds) {
        public RowCap {
            grantIds = List.copyOf(grantIds);
        }
    }

    public record BytesScannedLimit(long value, List<UUID> grantIds) {
        public BytesScannedLimit {
            grantIds = List.copyOf(grantIds);
        }
    }

    public EffectivePermissionExplanation {
        grants = List.copyOf(grants);
        capabilities = List.copyOf(capabilities);
        restrictedColumns = List.copyOf(restrictedColumns);
        deniedColumns = List.copyOf(deniedColumns);
        deniedSchemas = List.copyOf(deniedSchemas);
        deniedTables = List.copyOf(deniedTables);
        deniedShapes = List.copyOf(deniedShapes);
        tableRowLimits = List.copyOf(tableRowLimits);
        maskedColumns = List.copyOf(maskedColumns);
        revealedMasks = List.copyOf(revealedMasks);
        rowSecurity = List.copyOf(rowSecurity);
        groupNames = Map.copyOf(groupNames);
    }
}
