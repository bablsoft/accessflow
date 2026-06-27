package com.bablsoft.accessflow.apigov.events;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Published when an API request reaches a decision-driven status (APPROVED / REJECTED / EXECUTED /
 * FAILED / BREAK_GLASS-executed) — consumed by notifications. {@code reason} is optional context.
 */
public record ApiRequestDecidedEvent(UUID apiRequestId, QueryStatus status, String reason) {
}
