package com.bablsoft.accessflow.sqlreview.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD over an organization's SQL review rulesets (#861, epic #860; implemented in #863).
 * Every method is organization-scoped: a ruleset outside the caller's organization is
 * indistinguishable from a missing one ({@link SqlReviewRulesetNotFoundException}). Ruleset
 * <em>resolution</em> for a datasource is not exposed here — it is owned by
 * {@link SqlReviewService}, whose semantics (a bound-but-disabled ruleset yields no rules and never
 * falls through to the default) are the only ones the engine honours.
 */
public interface SqlReviewRulesetService {

    /** Every ruleset in the organization, ordered by name. */
    List<SqlReviewRulesetView> list(UUID organizationId);

    /** @throws SqlReviewRulesetNotFoundException when missing or in another organization */
    SqlReviewRulesetView get(UUID organizationId, UUID rulesetId);

    /**
     * Creates a ruleset. Rule configs are validated first ({@link IllegalSqlReviewRulesetException}).
     *
     * @throws SqlReviewRulesetConflictException for a second ruleset on the same environment, or a
     *                                           second organization default
     */
    SqlReviewRulesetView create(UUID organizationId, CreateSqlReviewRulesetCommand command);

    /**
     * Applies a partial update; a non-null {@code rules} list replaces the rule-config set wholesale.
     *
     * @throws SqlReviewRulesetNotFoundException when missing or in another organization
     * @throws SqlReviewRulesetConflictException when the new binding is taken by another ruleset
     * @throws IllegalSqlReviewRulesetException  when a supplied rule config is malformed
     */
    SqlReviewRulesetView update(UUID organizationId, UUID rulesetId, UpdateSqlReviewRulesetCommand command);

    /** @throws SqlReviewRulesetNotFoundException when missing or in another organization */
    void delete(UUID organizationId, UUID rulesetId);
}
