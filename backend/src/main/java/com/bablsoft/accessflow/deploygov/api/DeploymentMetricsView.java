package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DORA and approval-SLA metrics over one window (#984). Percentiles are whole seconds and
 * {@code null} on an empty sample, never {@code 0}; rates are {@code null} when their denominator
 * is {@code 0}. {@code daily} holds one zero-filled entry per UTC day the window touches.
 */
public record DeploymentMetricsView(
        Period period,
        DeploymentFrequency deploymentFrequency,
        LeadTime leadTime,
        ChangeFailureRate changeFailureRate,
        TimeToRestore timeToRestore,
        ApprovalLatency approvalLatency,
        TimeoutRate timeoutRate,
        long breakGlassCount,
        List<DailyBucket> daily) {

    public record Period(Instant from, Instant to) {
    }

    public record DeploymentFrequency(long total, double perDay) {
    }

    public record LeadTime(Long p50Seconds, Long p95Seconds, long sampleSize,
                           LeadTimeSource source) {
    }

    public record ChangeFailureRate(Double rate, long failed, long rolledBack, long unreported,
                                    long executed) {
    }

    public record TimeToRestore(Long p50Seconds, Long p95Seconds, long restored,
                                long openIncidents) {
    }

    public record ApprovalLatency(Long p50Seconds, Long p95Seconds, long sampleSize) {
    }

    public record TimeoutRate(Double rate, long timedOut, long enteredReview) {
    }

    public record DailyBucket(LocalDate date, long deployments, long failed, long rolledBack) {
    }
}
