package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.LifecyclePreviewResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunKind;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.events.LifecycleScanCompletedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scans enabled retention policies, computes eligibility via the dry-run calculator, and stages a
 * {@code lifecycle_runs} row per policy that has eligible rows and no pending run yet. Idempotent:
 * a policy with an existing {@code STAGED} run is skipped, so re-runs do not duplicate work. Actual
 * execution of staged runs (the proxy DELETE/UPDATE/pseudonymize) is a separate concern.
 */
@Service
@RequiredArgsConstructor
public class RetentionPolicyScanService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyScanService.class);

    private final RetentionPolicyRepository policyRepository;
    private final LifecycleRunRepository runRepository;
    private final LifecyclePreviewCalculator previewCalculator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** @return the number of staged runs across all enabled policies this cycle. */
    public int scanAndStage() {
        List<RetentionPolicyEntity> policies = policyRepository.findAllByEnabledTrue();
        if (policies.isEmpty()) {
            log.debug("No enabled retention policies to scan");
            return 0;
        }
        int staged = 0;
        for (RetentionPolicyEntity policy : policies) {
            try {
                if (stageIfEligible(policy)) {
                    staged++;
                }
            } catch (RuntimeException ex) {
                log.error("Retention scan failed for policy {}", policy.getId(), ex);
            }
        }
        eventPublisher.publishEvent(new LifecycleScanCompletedEvent(null, policies.size(), staged));
        log.info("Retention scan staged {} runs (scanned {} policies)", staged, policies.size());
        return staged;
    }

    @Transactional
    boolean stageIfEligible(RetentionPolicyEntity policy) {
        if (runRepository.existsByPolicyIdAndStatus(policy.getId(), LifecycleRunStatus.STAGED)) {
            return false;
        }
        // AF-519: a policy carrying a cron schedule is only staged when its schedule is due; the
        // cron bookkeeping (last/next run) advances on each due tick regardless of eligibility, so a
        // barren tick does not re-fire every poll. Null-cron policies keep the every-tick behavior.
        if (!cronDue(policy)) {
            return false;
        }
        LifecyclePreviewResult preview = previewCalculator.preview(policy);
        if (preview.totalEstimatedRows() <= 0) {
            return false;
        }
        var run = new LifecycleRunEntity();
        run.setId(UUID.randomUUID());
        run.setOrganizationId(policy.getOrganizationId());
        run.setDatasourceId(policy.getDatasourceId());
        run.setKind(LifecycleRunKind.RETENTION_POLICY);
        run.setPolicyId(policy.getId());
        run.setStatus(LifecycleRunStatus.STAGED);
        run.setAction(policy.getAction());
        run.setMethod(LifecyclePreviewCalculator.methodLabel(policy.getAction(),
                policy.getTransformType(), policy.getSoftDeleteColumn()));
        run.setAffectedRows(0);
        run.setCreatedAt(clock.instant());
        run.setUpdatedAt(clock.instant());
        runRepository.save(run);
        return true;
    }

    /**
     * @return {@code true} if the policy has no cron (fires every tick) or its cron is due now.
     * When a cron policy is due, its {@code lastRunAt}/{@code nextRunAt} bookkeeping is advanced and
     * persisted. A brand-new cron policy ({@code lastRunAt == null}) is due immediately.
     */
    private boolean cronDue(RetentionPolicyEntity policy) {
        String cron = policy.getCronSchedule();
        if (cron == null || cron.isBlank()) {
            return true;
        }
        CronExpression expr;
        try {
            expr = CronExpression.parse(cron.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("Retention policy {} has an unparseable cron '{}'; skipping", policy.getId(), cron);
            return false;
        }
        var now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (policy.getLastRunAt() != null) {
            var due = expr.next(ZonedDateTime.ofInstant(policy.getLastRunAt(), ZoneOffset.UTC));
            if (due == null || now.isBefore(due)) {
                return false;
            }
        }
        policy.setLastRunAt(now.toInstant());
        var next = expr.next(now);
        policy.setNextRunAt(next == null ? null : next.toInstant());
        policyRepository.save(policy);
        return true;
    }
}
