package com.bablsoft.accessflow.sqlreview.events;

import java.util.Objects;
import java.util.UUID;

/**
 * An organization's custom SQL review rules changed — one was created, edited, enabled, disabled or
 * deleted (#1009). Evicts that organization's cached rule set.
 */
public record SqlReviewCustomRuleChangedEvent(UUID organizationId) {

    public SqlReviewCustomRuleChangedEvent {
        Objects.requireNonNull(organizationId, "organizationId");
    }
}
