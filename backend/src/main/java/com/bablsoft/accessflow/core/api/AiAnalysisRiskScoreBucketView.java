package com.bablsoft.accessflow.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AiAnalysisRiskScoreBucketView(
        LocalDate date,
        BigDecimal successAvgRiskScore,
        long totalCount,
        long successCount
) {
}
