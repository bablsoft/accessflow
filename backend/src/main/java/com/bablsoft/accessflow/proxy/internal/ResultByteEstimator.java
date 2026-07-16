package com.bablsoft.accessflow.proxy.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Rough in-heap size estimate of a materialized result row (issue #49). The estimate is a
 * memory-protection backstop for the {@code max-result-bytes} cap, not byte-accurate accounting —
 * it only needs to be proportional to the real retained size.
 */
final class ResultByteEstimator {

    private static final long NULL_BYTES = 4;
    private static final long ROW_OVERHEAD_BYTES = 32;
    private static final long STRING_OVERHEAD_BYTES = 40;
    private static final long LIST_OVERHEAD_BYTES = 16;

    private ResultByteEstimator() {
    }

    /** Estimated retained heap size of one row's values, in bytes. */
    static long estimateRow(List<Object> row) {
        long total = ROW_OVERHEAD_BYTES;
        for (var value : row) {
            total += estimateValue(value);
        }
        return total;
    }

    private static long estimateValue(Object value) {
        return switch (value) {
            case null -> NULL_BYTES;
            case Boolean b -> 1;
            case Integer i -> 4;
            case Float f -> 4;
            case Long l -> 8;
            case Double d -> 8;
            case BigDecimal bd -> bd.unscaledValue().bitLength() / 8 + 8;
            case String s -> 2L * s.length() + STRING_OVERHEAD_BYTES;
            case OffsetDateTime odt -> 32;
            case List<?> list -> {
                long sum = LIST_OVERHEAD_BYTES;
                for (var element : list) {
                    sum += estimateValue(element);
                }
                yield sum;
            }
            default -> 2L * value.toString().length();
        };
    }
}
