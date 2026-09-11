package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/**
 * Traces one hypothetical API call through the evaluators that decide real ones (issue AF-967) — the
 * connector gates, the write classification, the schema catalog, the effective connector permission,
 * every routing policy in priority order, the review requirement, who could review it, and the masking
 * rules that would rewrite the response.
 *
 * <p><strong>Read-only.</strong> A simulation creates no {@code api_requests} row, publishes no event,
 * sends no notification, makes no AI call, and never contacts the governed third-party API. That is
 * enforced structurally rather than by convention: the implementation is wired only to lookup
 * services, and its test asserts as much against its declared field types.
 *
 * <p>Not to be confused with the query-side {@code workflow.api.AccessSimulationService}: the stages
 * differ per governed request kind, and only the trace vocabulary in {@code core.api} is shared.
 */
public interface ApiCallSimulationService {

    ApiCallSimulationResult simulate(UUID organizationId, ApiCallSimulationInput input);
}
