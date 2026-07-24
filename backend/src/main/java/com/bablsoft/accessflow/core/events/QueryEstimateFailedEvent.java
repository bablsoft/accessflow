package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when the pre-flight cost estimate (issue AF-624) hit an unexpected error — the
 * sentinel {@code query_estimates} row is persisted with {@code failed=true} and this event lets
 * the realtime module refresh open detail views so the failure surface renders.
 */
public record QueryEstimateFailedEvent(UUID queryRequestId, String reason) {
}
