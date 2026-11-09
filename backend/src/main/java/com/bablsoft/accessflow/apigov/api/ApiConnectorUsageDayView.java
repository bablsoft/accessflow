package com.bablsoft.accessflow.apigov.api;

import java.time.LocalDate;

/** One UTC day of a connector's usage (#973). */
public record ApiConnectorUsageDayView(LocalDate date, long calls, long failedCalls,
                                       long throttledCalls, long spendMicros) {
}
