package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.audit.api.BehaviorAuditSample;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a window's worth of {@link BehaviorAuditSample}s into a {@link WindowFeatures}. Hours are
 * bucketed in the application {@link Clock}'s zone (the single UTC clock bean) so the baseline and
 * the observed window are always compared in the same zone. Samples without a {@code rows_returned}
 * value don't contribute to the rows-returned mean (which stays null if none reported one).
 */
@Component
@RequiredArgsConstructor
class BehaviorFeatureExtractor {

    private final Clock clock;

    WindowFeatures extract(List<BehaviorAuditSample> samples, Instant windowStart, Instant windowEnd) {
        int[] hourHistogram = new int[24];
        Set<Integer> activeHours = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        Map<String, Integer> queryTypeCounts = new LinkedHashMap<>();
        int errors = 0;
        double rowsSum = 0.0;
        int rowsCount = 0;
        var zone = clock.getZone();

        for (BehaviorAuditSample sample : samples) {
            int hour = sample.occurredAt().atZone(zone).getHour();
            hourHistogram[hour]++;
            activeHours.add(hour);
            tables.addAll(sample.referencedTables());
            if (sample.queryType() != null && !sample.queryType().isBlank()) {
                queryTypeCounts.merge(sample.queryType(), 1, Integer::sum);
            }
            if (!sample.success()) {
                errors++;
            }
            if (sample.success() && sample.rowsReturned() != null) {
                rowsSum += sample.rowsReturned();
                rowsCount++;
            }
        }

        int queryCount = samples.size();
        double errorRate = queryCount == 0 ? 0.0 : (double) errors / queryCount;
        Double meanRowsReturned = rowsCount == 0 ? null : rowsSum / rowsCount;

        return new WindowFeatures(windowStart, windowEnd, queryCount, hourHistogram, tables.size(),
                tables, queryTypeCounts, meanRowsReturned, errorRate, activeHours);
    }
}
