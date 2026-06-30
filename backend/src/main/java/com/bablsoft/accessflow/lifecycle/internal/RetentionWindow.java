package com.bablsoft.accessflow.lifecycle.internal;

import java.time.Duration;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;

/**
 * Parses a retention window expressed as an ISO-8601 period ({@code P7Y}, {@code P6M}, {@code P30D})
 * or duration ({@code PT720H}). Date-based and time-based forms are both accepted; the cutoff is
 * computed against UTC.
 */
final class RetentionWindow {

    private final TemporalAmount amount;

    private RetentionWindow(TemporalAmount amount) {
        this.amount = amount;
    }

    /** @throws IllegalArgumentException when the text is not a parseable, positive ISO-8601 window. */
    static RetentionWindow parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("retention window must not be blank");
        }
        String trimmed = text.trim();
        RuntimeException last;
        try {
            Period period = Period.parse(trimmed);
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("retention window must be positive");
            }
            return new RetentionWindow(period);
        } catch (RuntimeException ex) {
            last = ex;
        }
        try {
            Duration duration = Duration.parse(trimmed);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("retention window must be positive");
            }
            return new RetentionWindow(duration);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid retention window: " + text, last);
        }
    }

    /** @return the cutoff instant — rows older than this are eligible for the policy action. */
    ZonedDateTime cutoffFrom(ZonedDateTime now) {
        return now.withZoneSameInstant(ZoneOffset.UTC).minus(amount);
    }
}
