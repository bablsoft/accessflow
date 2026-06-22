package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One datasource the caller may currently break-glass on (AF-385): a non-expired
 * {@code can_break_glass} grant. {@code expiresAt} is null for a standing grant.
 */
public record BreakGlassEligibility(UUID datasourceId, Instant expiresAt) {
}
