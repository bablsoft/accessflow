package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A ruleset on the wire. {@code environment} and {@code description} are omitted when unset. */
public record SqlReviewRulesetResponse(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        DatasourceEnvironment environment,
        boolean enabled,
        List<SqlReviewRuleConfigResponse> rules,
        Instant createdAt,
        Instant updatedAt
) {
    public static SqlReviewRulesetResponse from(SqlReviewRulesetView view) {
        return new SqlReviewRulesetResponse(view.id(), view.organizationId(), view.name(), view.description(),
                view.environment(), view.enabled(),
                view.rules().stream().map(SqlReviewRuleConfigResponse::from).toList(),
                view.createdAt(), view.updatedAt());
    }
}
