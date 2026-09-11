package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/**
 * The governance-relevant fields of one connector — everything the decision chain reads and nothing
 * else (issue AF-967).
 *
 * <p>Deliberately narrower than {@link ApiConnectorView}: the decision explainer needs the review
 * flags and the plan id, not the base URL, the auth method or the OAuth2 configuration. Keeping it
 * narrow is also what lets the simulator depend on a lookup interface rather than on the connector
 * admin service, which carries {@code create}, {@code update}, {@code delete} and an outbound
 * {@code test} probe.
 */
public record ApiConnectorGovernanceView(UUID id, UUID organizationId, String name,
                                         ApiProtocol protocol, boolean active,
                                         boolean aiAnalysisEnabled, UUID reviewPlanId,
                                         boolean requireReviewReads, boolean requireReviewWrites) {
}
