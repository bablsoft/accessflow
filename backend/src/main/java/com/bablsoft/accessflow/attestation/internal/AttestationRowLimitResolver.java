package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourcePermissionView;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.proxy.api.RowCapResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the row limit enforcement applies to a reviewed grant's subject (#1084). The merge is
 * {@link DatasourceUserPermissionLookupService#mergeContributions} and the clamp is
 * {@link RowCapResolver} — the executor's own — so evidence cannot disagree with enforcement. This
 * class only names which bound set the value.
 */
@Component
@RequiredArgsConstructor
class AttestationRowLimitResolver {

    static final String SOURCE_GRANT = "grant";
    static final String SOURCE_GROUP_PREFIX = "group:";
    static final String SOURCE_DATASOURCE_CAP = "datasource_cap";
    static final String SOURCE_GLOBAL_CEILING = "global_ceiling";

    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final RowCapResolver rowCapResolver;

    /** Loads the datasource's live contributions once, for every grant under review on it. */
    DatasourceRowLimits forDatasource(UUID datasourceId, int maxRowsPerQuery) {
        var byUser = permissionLookupService.findContributionsForDatasource(datasourceId).stream()
                .collect(Collectors.groupingBy(DatasourcePermissionContribution::userId));
        return new DatasourceRowLimits(byUser, maxRowsPerQuery);
    }

    /** The configured override on the reviewed grant, the limit that applies, and what set it. */
    record RowLimitEvidence(Integer configured, int effective, String source) {
    }

    final class DatasourceRowLimits {

        private final Map<UUID, List<DatasourcePermissionContribution>> byUser;
        private final int maxRowsPerQuery;

        private DatasourceRowLimits(Map<UUID, List<DatasourcePermissionContribution>> byUser,
                                    int maxRowsPerQuery) {
            this.byUser = byUser;
            this.maxRowsPerQuery = maxRowsPerQuery;
        }

        RowLimitEvidence evaluate(DatasourcePermissionView reviewed) {
            var parts = byUser.getOrDefault(reviewed.userId(), List.of());
            var override = permissionLookupService.mergeContributions(parts)
                    .map(merged -> merged.rowLimitOverride())
                    .orElse(null);
            var cap = rowCapResolver.resolve(override, maxRowsPerQuery);
            var source = switch (cap.source()) {
                case OVERRIDE -> overrideSource(reviewed, parts, override);
                case DATASOURCE_CAP -> SOURCE_DATASOURCE_CAP;
                case GLOBAL_CEILING -> SOURCE_GLOBAL_CEILING;
                // EffectiveRowCap.of never reports a per-table policy (#934) — it depends on the
                // query's tables, which a standing grant has none of. Unreachable; named, not thrown,
                // so a future change cannot fail a whole campaign open.
                case ROW_LIMIT_POLICY -> "row_limit_policy";
            };
            return new RowLimitEvidence(reviewed.rowLimitOverride(), cap.value(), source);
        }

        private static String overrideSource(DatasourcePermissionView reviewed,
                                             List<DatasourcePermissionContribution> parts,
                                             Integer override) {
            // On a tie the grant under review is credited: it is what the reviewer certifies.
            if (Objects.equals(reviewed.rowLimitOverride(), override)) {
                return SOURCE_GRANT;
            }
            return parts.stream()
                    .filter(c -> c.sourceKind() == DatasourcePermissionSourceKind.GROUP)
                    .filter(c -> Objects.equals(c.rowLimitOverride(), override))
                    .findFirst()
                    .map(c -> SOURCE_GROUP_PREFIX + c.groupName())
                    .orElse(SOURCE_GRANT);
        }
    }
}
