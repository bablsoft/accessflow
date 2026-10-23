package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView;
import com.bablsoft.accessflow.deploygov.api.LeadTimeSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DeploymentMetricsResponse(
        PeriodResponse period,
        FrequencyResponse deploymentFrequency,
        LeadTimeResponse leadTime,
        ChangeFailureRateResponse changeFailureRate,
        TimeToRestoreResponse timeToRestore,
        ApprovalLatencyResponse approvalLatency,
        TimeoutRateResponse timeoutRate,
        long breakGlassCount,
        List<DailyResponse> daily) {

    record PeriodResponse(Instant from, Instant to) {
    }

    record FrequencyResponse(long total, double perDay) {
    }

    record LeadTimeResponse(Long p50Seconds, Long p95Seconds, long sampleSize,
                            LeadTimeSource source) {
    }

    record ChangeFailureRateResponse(Double rate, long failed, long rolledBack, long unreported,
                                     long executed) {
    }

    record TimeToRestoreResponse(Long p50Seconds, Long p95Seconds, long restored,
                                 long openIncidents) {
    }

    record ApprovalLatencyResponse(Long p50Seconds, Long p95Seconds, long sampleSize) {
    }

    record TimeoutRateResponse(Double rate, long timedOut, long enteredReview) {
    }

    record DailyResponse(LocalDate date, long deployments, long failed, long rolledBack) {
    }

    static DeploymentMetricsResponse from(DeploymentMetricsView view) {
        var lead = view.leadTime();
        var cfr = view.changeFailureRate();
        var restore = view.timeToRestore();
        var approval = view.approvalLatency();
        var timeout = view.timeoutRate();
        return new DeploymentMetricsResponse(
                new PeriodResponse(view.period().from(), view.period().to()),
                new FrequencyResponse(view.deploymentFrequency().total(),
                        view.deploymentFrequency().perDay()),
                new LeadTimeResponse(lead.p50Seconds(), lead.p95Seconds(), lead.sampleSize(),
                        lead.source()),
                new ChangeFailureRateResponse(cfr.rate(), cfr.failed(), cfr.rolledBack(),
                        cfr.unreported(), cfr.executed()),
                new TimeToRestoreResponse(restore.p50Seconds(), restore.p95Seconds(),
                        restore.restored(), restore.openIncidents()),
                new ApprovalLatencyResponse(approval.p50Seconds(), approval.p95Seconds(),
                        approval.sampleSize()),
                new TimeoutRateResponse(timeout.rate(), timeout.timedOut(),
                        timeout.enteredReview()),
                view.breakGlassCount(),
                view.daily().stream()
                        .map(day -> new DailyResponse(day.date(), day.deployments(), day.failed(),
                                day.rolledBack()))
                        .toList());
    }
}
