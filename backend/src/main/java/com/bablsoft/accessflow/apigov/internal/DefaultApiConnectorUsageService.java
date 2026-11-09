package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorUsageDayView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorUsageService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorUsageView;
import com.bablsoft.accessflow.apigov.api.ApiUsageOutcome;
import com.bablsoft.accessflow.apigov.api.IllegalApiUsageRangeException;
import com.bablsoft.accessflow.apigov.events.ApiConnectorBudgetExceededEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUsageDailyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUsageDailyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Accrues governed calls into {@code api_connector_usage_daily} and reads them back (#973). Day and
 * month boundaries are UTC, computed here from the injected {@link Clock} — never {@code date_trunc},
 * which buckets by the session zone. Accrual joins the caller's execution transaction.
 */
@Service
@RequiredArgsConstructor
class DefaultApiConnectorUsageService implements ApiConnectorUsageService {

    static final int MAX_RANGE_DAYS = 92;

    private static final Logger log = LoggerFactory.getLogger(DefaultApiConnectorUsageService.class);

    private final ApiConnectorRepository connectorRepository;
    private final ApiConnectorUsageDailyRepository usageRepository;
    private final EffectiveApiConnectorPermissionResolver permissionResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public long accrue(UUID connectorId, UUID organizationId, UUID userId, ApiUsageOutcome outcome,
                       int units) {
        var connector = connectorRepository.findById(connectorId).orElse(null);
        if (connector == null) {
            return 0;
        }
        int count = Math.max(1, units);
        long spend = outcome == ApiUsageOutcome.THROTTLED ? 0 : Math.multiplyExact(price(connector), count);
        var today = today();
        usageRepository.accumulate(UUID.randomUUID(), organizationId, connectorId, userId, today,
                outcome == ApiUsageOutcome.THROTTLED ? 0 : count,
                outcome == ApiUsageOutcome.FAILED ? count : 0,
                outcome == ApiUsageOutcome.THROTTLED ? count : 0,
                spend, clock.instant());
        if (spend > 0) {
            checkBudget(connector, today);
        }
        return spend;
    }

    private void checkBudget(ApiConnectorEntity connector, LocalDate today) {
        long budget = budget(connector);
        if (budget <= 0) {
            return;
        }
        var monthStart = today.withDayOfMonth(1);
        long spent = usageRepository.sumSpendMicros(connector.getId(), monthStart);
        if (spent < budget) {
            return;
        }
        if (connectorRepository.markBudgetExceededNotified(connector.getId(), monthStart) == 1) {
            log.info("API connector {} reached its monthly budget: {} of {} micros", connector.getId(),
                    spent, budget);
            eventPublisher.publishEvent(new ApiConnectorBudgetExceededEvent(connector.getId(),
                    connector.getOrganizationId(), spent, budget));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Integer budgetUsedPercent(UUID connectorId) {
        return connectorRepository.findById(connectorId)
                .map(connector -> budget(connector) <= 0 || price(connector) <= 0 ? null
                        : percent(connector, usageRepository.sumSpendMicros(connectorId,
                                today().withDayOfMonth(1))))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiConnectorUsageView usage(UUID connectorId, UUID organizationId, UUID userId,
                                       LocalDate from, LocalDate to) {
        var connector = connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
        if (userId != null) {
            requireGranted(connectorId, userId);
        }
        var today = today();
        var monthStart = today.withDayOfMonth(1);
        var start = from != null ? from : monthStart;
        var end = to != null ? to : today;
        if (start.isAfter(end)) {
            throw new IllegalApiUsageRangeException("Usage range 'from' is after 'to'", MAX_RANGE_DAYS);
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalApiUsageRangeException("Usage range exceeds " + MAX_RANGE_DAYS + " days",
                    MAX_RANGE_DAYS);
        }
        var rows = userId == null
                ? usageRepository.findByConnectorIdAndUsageDateBetweenOrderByUsageDateAsc(connectorId, start, end)
                : usageRepository.findByConnectorIdAndUserIdAndUsageDateBetweenOrderByUsageDateAsc(
                        connectorId, userId, start, end);
        long monthToDate = usageRepository.sumSpendMicros(connectorId, monthStart);
        return new ApiConnectorUsageView(connectorId, monthStart, monthToDate,
                connector.getMonthlyBudgetMicros(), percent(connector, monthToDate),
                connector.getCostPerCallMicros(), denseDays(rows, start, end));
    }

    private void requireGranted(UUID connectorId, UUID userId) {
        // Same visibility as GET /api-connectors/{id}: an ungranted user gets 404, never 403.
        var permission = permissionResolver.resolve(connectorId, userId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
        if (!permission.canRead() && !permission.canWrite()) {
            throw new ApiConnectorNotFoundException(connectorId);
        }
    }

    private static Integer percent(ApiConnectorEntity connector, long spent) {
        long budget = budget(connector);
        if (budget <= 0 || price(connector) <= 0) {
            return null;
        }
        long pct = spent > Long.MAX_VALUE / 100 ? Long.MAX_VALUE : spent * 100 / budget;
        return (int) Math.min(pct, Integer.MAX_VALUE);
    }

    private static List<ApiConnectorUsageDayView> denseDays(List<ApiConnectorUsageDailyEntity> rows,
                                                            LocalDate start, LocalDate end) {
        var byDate = new TreeMap<LocalDate, long[]>();
        for (var row : rows) {
            var totals = byDate.computeIfAbsent(row.getUsageDate(), d -> new long[4]);
            totals[0] += row.getCalls();
            totals[1] += row.getFailedCalls();
            totals[2] += row.getThrottledCalls();
            totals[3] += row.getSpendMicros();
        }
        var days = new ArrayList<ApiConnectorUsageDayView>();
        for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
            var totals = byDate.getOrDefault(date, new long[4]);
            days.add(new ApiConnectorUsageDayView(date, totals[0], totals[1], totals[2], totals[3]));
        }
        return List.copyOf(days);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static long price(ApiConnectorEntity connector) {
        var price = connector.getCostPerCallMicros();
        return price == null ? 0 : price;
    }

    private static long budget(ApiConnectorEntity connector) {
        var budget = connector.getMonthlyBudgetMicros();
        return budget == null ? 0 : budget;
    }
}
