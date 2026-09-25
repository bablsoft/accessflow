package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataBudgetConsumption;
import com.bablsoft.accessflow.core.api.DataBudgetStatusService;
import com.bablsoft.accessflow.core.api.DataBudgetUsageRecord;
import com.bablsoft.accessflow.core.api.DataBudgetUsageService;
import com.bablsoft.accessflow.core.events.DataBudgetThresholdCrossedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetUsageEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDataBudgetUsageService implements DataBudgetUsageService {

    private final DataBudgetStatusService statusService;
    private final DataBudgetRepository dataBudgetRepository;
    private final DataBudgetUsageRepository usageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(DataBudgetUsageRecord usage) {
        // Without the lock two concurrent charges read the same `before`: a crossing that only
        // their sum makes would never notify, and one both make would notify twice.
        usageRepository.lockUserDatasource(lockKey(usage.userId(), usage.datasourceId()));
        var before = statusService.statusFor(usage.datasourceId(), usage.userId());
        if (before.isEmpty()) {
            return;
        }
        var organizationId = dataBudgetRepository.findById(before.budgets().getFirst().budgetId())
                .map(b -> b.getOrganizationId())
                .orElse(null);
        if (organizationId == null) {
            return;
        }
        var entry = new DataBudgetUsageEntity();
        entry.setId(UUID.randomUUID());
        entry.setOrganizationId(organizationId);
        entry.setUserId(usage.userId());
        entry.setDatasourceId(usage.datasourceId());
        entry.setRowsRead(usage.rowsRead());
        entry.setBytesRead(usage.bytesRead());
        entry.setSource(usage.source());
        entry.setQueryRequestId(usage.queryRequestId());
        entry.setRequestGroupId(usage.requestGroupId());
        entry.setOccurredAt(clock.instant());
        usageRepository.save(entry);
        for (var budget : before.budgets()) {
            var after = budget.plus(usage.rowsRead(), usage.bytesRead());
            crossing(budget, after).ifPresent(exhausted -> eventPublisher.publishEvent(
                    toEvent(organizationId, usage, after, exhausted)));
        }
    }

    static long lockKey(UUID userId, UUID datasourceId) {
        return userId.getMostSignificantBits() ^ Long.rotateLeft(userId.getLeastSignificantBits(), 17)
                ^ Long.rotateLeft(datasourceId.getMostSignificantBits(), 31)
                ^ datasourceId.getLeastSignificantBits();
    }

    /** Empty when no mark was crossed; true for the limit, false for the warn threshold. */
    static Optional<Boolean> crossing(DataBudgetConsumption before,
                                                DataBudgetConsumption after) {
        if (!before.exhausted() && after.exhausted()) {
            return Optional.of(true);
        }
        var threshold = before.warnThresholdPercent();
        if (threshold != null && !after.exhausted()
                && before.usedPercent() < threshold && after.usedPercent() >= threshold) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private static DataBudgetThresholdCrossedEvent toEvent(UUID organizationId,
                                                           DataBudgetUsageRecord usage,
                                                           DataBudgetConsumption after,
                                                           boolean exhausted) {
        return new DataBudgetThresholdCrossedEvent(
                organizationId,
                usage.userId(),
                usage.datasourceId(),
                after.budgetId(),
                after.name(),
                exhausted,
                after.warnThresholdPercent(),
                (int) Math.min(100, Math.floor(after.usedPercent())),
                after.maxRows(),
                after.maxBytes(),
                after.usedRows(),
                after.usedBytes(),
                after.windowMinutes(),
                after.breachAction());
    }
}
