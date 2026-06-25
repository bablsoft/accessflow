package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Self-scoped operations over the caller's <em>own</em> behavioural anomalies (UBA, AF-383),
 * powering the personalized dashboard's anomaly-alerts widget (AF-498). Unlike
 * {@link BehaviorAnomalyAdminService} (ADMIN/AUDITOR, org-wide), every method here is bound to a
 * single {@code userId} and refuses to touch another user's rows — an anomaly that exists but
 * belongs to a different user is reported as {@link AnomalyNotFoundException}, never leaked.
 */
public interface UserBehaviorAnomalyService {

    /**
     * Lists the user's own anomalies, optionally filtered by {@code status} (null = all), ordered by
     * {@code detectedAt} descending.
     */
    PageResponse<BehaviorAnomalyView> listForUser(UUID organizationId, UUID userId,
                                                  BehaviorAnomalyStatus status, PageRequest pageRequest);

    /**
     * Transition the user's own OPEN anomaly to ACKNOWLEDGED, stamping the user as the actor.
     *
     * @throws AnomalyNotFoundException                when absent / cross-tenant / not owned by the user.
     * @throws IllegalAnomalyStatusTransitionException when the anomaly is not currently OPEN.
     */
    BehaviorAnomalyView acknowledgeOwn(UUID organizationId, UUID userId, UUID anomalyId);

    /**
     * Transition the user's own OPEN/ACKNOWLEDGED anomaly to DISMISSED, stamping the user as the actor.
     *
     * @throws AnomalyNotFoundException                when absent / cross-tenant / not owned by the user.
     * @throws IllegalAnomalyStatusTransitionException when the anomaly is already DISMISSED.
     */
    BehaviorAnomalyView dismissOwn(UUID organizationId, UUID userId, UUID anomalyId);
}
