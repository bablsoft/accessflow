package com.bablsoft.accessflow.engine.databricks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Extracts the top-level {@code Statistics(sizeInBytes=…, rowCount=…)} annotation from an
 * {@code EXPLAIN COST} plan text (AF-634). Catalyst prints the optimized logical plan with a
 * statistics annotation per node, top operator first — the first match is the whole query's
 * estimate. {@code sizeInBytes} carries binary-unit suffixes ({@code B}, {@code KiB} … {@code
 * EiB}) and either notation ({@code 4.2}, {@code 1.2E+5}); {@code rowCount} may be absent.
 * Deliberately never throws — the plan-text grammar is not contractual, so any drift degrades to
 * a {@code null} estimate while the raw plan text still reaches the user. Catalyst substitutes
 * {@code spark.sql.defaultSizeInBytes} (= {@code Long.MaxValue}, printed {@code 8.0 EiB}) for
 * relations whose size it cannot determine, so sizes at or above that sentinel are treated as
 * unknown ({@code null}), not as a real estimate.
 */
final class DatabricksExplainCostParser {

    private static final Pattern STATISTICS = Pattern.compile(
            "Statistics\\(\\s*sizeInBytes=(?<size>[0-9.Ee+\\-]+)\\s*(?<unit>[KMGTPE]iB|B)"
                    + "(?:\\s*,\\s*rowCount=(?<rows>[0-9.Ee+\\-]+))?");

    /** The parsed top-level estimate; either component may be {@code null}. */
    record CostEstimate(Long sizeInBytes, Long rowCount) {
        static final CostEstimate EMPTY = new CostEstimate(null, null);
    }

    private DatabricksExplainCostParser() {
    }

    static CostEstimate parse(String planText) {
        if (planText == null || planText.isBlank()) {
            return CostEstimate.EMPTY;
        }
        // Prefer the optimized logical plan section (EXPLAIN COST prints it before the physical
        // plan); fall back to the whole text when the section marker is absent.
        var optimizedAt = planText.indexOf("== Optimized Logical Plan ==");
        var matcher = STATISTICS.matcher(optimizedAt >= 0
                ? planText.substring(optimizedAt) : planText);
        if (!matcher.find()) {
            return CostEstimate.EMPTY;
        }
        return new CostEstimate(
                toBytes(matcher.group("size"), matcher.group("unit")),
                toLong(matcher.group("rows")));
    }

    private static Long toBytes(String size, String unit) {
        var value = decimal(size);
        if (value == null) {
            return null;
        }
        var scaled = value.multiply(BigDecimal.valueOf(unitFactor(unit)));
        // At/above Long.MaxValue this is Catalyst's unknown-size sentinel, not an estimate.
        if (scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0 || scaled.signum() < 0) {
            return null;
        }
        return scaled.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static long unitFactor(String unit) {
        return switch (unit) {
            case "KiB" -> 1L << 10;
            case "MiB" -> 1L << 20;
            case "GiB" -> 1L << 30;
            case "TiB" -> 1L << 40;
            case "PiB" -> 1L << 50;
            case "EiB" -> 1L << 60;
            default -> 1L;
        };
    }

    private static Long toLong(String text) {
        var value = decimal(text);
        if (value == null || value.signum() < 0) {
            return null;
        }
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        return value.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static BigDecimal decimal(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
