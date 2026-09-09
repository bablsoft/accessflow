package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.SimulationCaveat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The diff between two mask resolutions over the same historical result sets — once under the
 * datasource's current masking policies, once with a draft applied (issue AF-630).
 *
 * <p>{@code evaluatedCount} counts only rows that actually carried column metadata. A query with no
 * persisted result set has no columns to compare and is skipped rather than silently counted as
 * unchanged, so the denominator the UI shows is honest.
 */
public record MaskingSimulationResult(
        Instant periodFrom,
        Instant periodTo,
        UUID datasourceId,
        int evaluatedCount,
        int changedCount,
        int newlyMaskedCount,
        int newlyRevealedCount,
        boolean truncated,
        List<UserImpact> userImpacts,
        List<ColumnImpact> columnImpacts,
        List<Sample> samples,
        List<SimulationCaveat> caveats) {

    public MaskingSimulationResult {
        userImpacts = userImpacts == null ? List.of() : List.copyOf(userImpacts);
        columnImpacts = columnImpacts == null ? List.of() : List.copyOf(columnImpacts);
        samples = samples == null ? List.of() : List.copyOf(samples);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }

    /** Per-submitter change in what they would see, ordered by affected query count descending. */
    public record UserImpact(UUID userId, String email, String displayName,
                             List<String> newlyMaskedColumns, List<String> newlyRevealedColumns,
                             int affectedQueryCount) {
        public UserImpact {
            newlyMaskedColumns = newlyMaskedColumns == null ? List.of()
                    : List.copyOf(newlyMaskedColumns);
            newlyRevealedColumns = newlyRevealedColumns == null ? List.of()
                    : List.copyOf(newlyRevealedColumns);
        }
    }

    /** How often each column's masked-ness flips across the window. */
    public record ColumnImpact(String columnName, int newlyMaskedQueryCount,
                               int newlyRevealedQueryCount) {
    }

    /**
     * One changed result set. Carries no SQL text and no cell values: {@code MASKING_POLICY_MANAGE}
     * does not otherwise grant read access to other people's queries or their results.
     */
    public record Sample(UUID queryRequestId, String submittedByEmail, Instant createdAt,
                         List<String> newlyMaskedColumns, List<String> newlyRevealedColumns) {
        public Sample {
            newlyMaskedColumns = newlyMaskedColumns == null ? List.of()
                    : List.copyOf(newlyMaskedColumns);
            newlyRevealedColumns = newlyRevealedColumns == null ? List.of()
                    : List.copyOf(newlyRevealedColumns);
        }
    }
}
