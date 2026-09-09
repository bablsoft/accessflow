package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.proxy.api.MaskingSimulationResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shape for a masking dry run (issue AF-630). */
public record MaskingSimulationResponse(
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

    public static MaskingSimulationResponse from(MaskingSimulationResult result) {
        return new MaskingSimulationResponse(result.periodFrom(), result.periodTo(),
                result.datasourceId(), result.evaluatedCount(), result.changedCount(),
                result.newlyMaskedCount(), result.newlyRevealedCount(), result.truncated(),
                result.userImpacts().stream()
                        .map(u -> new UserImpact(u.userId(), u.email(), u.displayName(),
                                u.newlyMaskedColumns(), u.newlyRevealedColumns(),
                                u.affectedQueryCount())).toList(),
                result.columnImpacts().stream()
                        .map(c -> new ColumnImpact(c.columnName(), c.newlyMaskedQueryCount(),
                                c.newlyRevealedQueryCount())).toList(),
                result.samples().stream()
                        .map(s -> new Sample(s.queryRequestId(), s.submittedByEmail(),
                                s.createdAt(), s.newlyMaskedColumns(), s.newlyRevealedColumns()))
                        .toList(),
                result.caveats());
    }

    public record UserImpact(UUID userId, String email, String displayName,
                             List<String> newlyMaskedColumns, List<String> newlyRevealedColumns,
                             int affectedQueryCount) {
    }

    public record ColumnImpact(String columnName, int newlyMaskedQueryCount,
                               int newlyRevealedQueryCount) {
    }

    public record Sample(UUID queryRequestId, String submittedByEmail, Instant createdAt,
                         List<String> newlyMaskedColumns, List<String> newlyRevealedColumns) {
    }
}
