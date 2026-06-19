package com.bablsoft.accessflow.compliance.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Parameters for a compliance report (#459): the report {@code type}, the half-open
 * {@code [from, to)} period over {@code executedAt}, and an optional {@code datasourceId} to scope
 * the report to a single datasource (null = all datasources in the organization).
 */
public record ComplianceReportRequest(ComplianceReportType type, Instant from, Instant to,
                                      UUID datasourceId) {
}
