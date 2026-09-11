package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin CRUD over an organization's SQL review rulesets (#861, epic #860; implemented in #863).
 * Every method is organization-scoped: a ruleset outside the caller's organization is
 * indistinguishable from a missing one.
 */
public interface SqlReviewRulesetService {

    /**
     * The ruleset a datasource in {@code environment} evaluates against: the environment-bound
     * ruleset, else the organization-wide default, else empty. A {@code null} environment goes
     * straight to the default. Disabled rulesets are never returned.
     */
    Optional<SqlReviewRulesetView> resolve(UUID organizationId, DatasourceEnvironment environment);

    List<SqlReviewRulesetView> list(UUID organizationId);

    SqlReviewRulesetView get(UUID organizationId, UUID rulesetId);

    /** Rejects a second ruleset for the same environment, or a second organization default. */
    SqlReviewRulesetView create(UUID organizationId, CreateSqlReviewRulesetCommand command);

    SqlReviewRulesetView update(UUID organizationId, UUID rulesetId, UpdateSqlReviewRulesetCommand command);

    void delete(UUID organizationId, UUID rulesetId);
}
