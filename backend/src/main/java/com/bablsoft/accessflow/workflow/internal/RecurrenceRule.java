package com.bablsoft.accessflow.workflow.internal;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Parsed recurrence rule of a recurring query series (#627): either a fixed ISO-8601 interval
 * ({@code P}/{@code p} prefix, e.g. {@code PT6H}) or a 6-field Spring cron expression evaluated in
 * <strong>UTC</strong> (e.g. {@code 0 0 8 * * MON}). Missed occurrences are never backfilled —
 * {@link #nextAfter(Instant)} always advances from the instant the caller supplies, which for the
 * recurring job is "now".
 */
sealed interface RecurrenceRule {

    /**
     * @throws IllegalArgumentException when {@code raw} is blank, not a valid ISO-8601 duration,
     *         not a valid 6-field Spring cron expression, or a non-positive duration.
     */
    static RecurrenceRule parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Recurrence rule must not be blank");
        }
        var trimmed = raw.trim();
        if (trimmed.charAt(0) == 'P' || trimmed.charAt(0) == 'p') {
            var interval = Duration.parse(trimmed);
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException(
                        "Recurrence interval must be positive: " + trimmed);
            }
            return new FixedInterval(interval);
        }
        return new Cron(CronExpression.parse(trimmed));
    }

    /**
     * The first occurrence strictly after {@code base}, or {@code null} when the rule yields no
     * further occurrence (a cron with no future match).
     */
    Instant nextAfter(Instant base);

    /**
     * The gap between the two occurrences following {@code from} — the effective cadence, used to
     * enforce the configured minimum interval. {@code null} when the rule yields fewer than two
     * further occurrences (nothing left to rate-limit).
     */
    Duration minGap(Instant from);

    record FixedInterval(Duration interval) implements RecurrenceRule {

        @Override
        public Instant nextAfter(Instant base) {
            return base.plus(interval);
        }

        @Override
        public Duration minGap(Instant from) {
            return interval;
        }
    }

    record Cron(CronExpression expression) implements RecurrenceRule {

        /** How many successive occurrences {@link #minGap} samples for the floor check. */
        private static final int MIN_GAP_SAMPLES = 32;

        /** Sampling horizon — irregular crons repeat within a week (day-of-week is their
         *  longest field), so a 8-day window sees every distinct gap of the cycle. */
        private static final Duration MIN_GAP_HORIZON = Duration.ofDays(8);

        @Override
        public Instant nextAfter(Instant base) {
            var next = expression.next(ZonedDateTime.ofInstant(base, ZoneOffset.UTC));
            return next != null ? next.toInstant() : null;
        }

        @Override
        public Duration minGap(Instant from) {
            // An irregular cron ("0 0,1 8 * * *") can hide a tight gap behind a wide first one,
            // so sample a window of successive occurrences and take the smallest gap.
            var previous = nextAfter(from);
            if (previous == null) {
                return null;
            }
            var horizon = previous.plus(MIN_GAP_HORIZON);
            Duration min = null;
            for (int i = 0; i < MIN_GAP_SAMPLES; i++) {
                var next = nextAfter(previous);
                if (next == null || next.isAfter(horizon)) {
                    break;
                }
                var gap = Duration.between(previous, next);
                if (min == null || gap.compareTo(min) < 0) {
                    min = gap;
                }
                previous = next;
            }
            return min;
        }
    }
}
