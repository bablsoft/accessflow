package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorUsageDayView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorUsageView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ApiConnectorUsageResponse(
        UUID connectorId,
        LocalDate monthStart,
        long monthToDateSpendMicros,
        Long monthlyBudgetMicros,
        Integer budgetUsedPercent,
        Long costPerCallMicros,
        List<Day> days) {

    public record Day(LocalDate date, long calls, long failedCalls, long throttledCalls, long spendMicros) {

        static Day from(ApiConnectorUsageDayView view) {
            return new Day(view.date(), view.calls(), view.failedCalls(), view.throttledCalls(),
                    view.spendMicros());
        }
    }

    public static ApiConnectorUsageResponse from(ApiConnectorUsageView view) {
        return new ApiConnectorUsageResponse(view.connectorId(), view.monthStart(),
                view.monthToDateSpendMicros(), view.monthlyBudgetMicros(), view.budgetUsedPercent(),
                view.costPerCallMicros(), view.days().stream().map(Day::from).toList());
    }
}
