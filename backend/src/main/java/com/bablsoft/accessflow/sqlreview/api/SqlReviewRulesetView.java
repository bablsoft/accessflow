package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A ruleset as exposed to admins.
 *
 * @param environment the environment this ruleset is bound to, or {@code null} for the
 *                    organization-wide default
 * @param rules       the per-rule configuration; never {@code null}
 */
public record SqlReviewRulesetView(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        DatasourceEnvironment environment,
        boolean enabled,
        List<SqlReviewRuleConfigView> rules,
        Instant createdAt,
        Instant updatedAt
) {
    public SqlReviewRulesetView {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
