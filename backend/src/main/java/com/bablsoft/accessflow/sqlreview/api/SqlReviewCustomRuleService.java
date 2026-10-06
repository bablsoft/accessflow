package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DbType;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD over an organization's custom SQL review rules, plus a side-effect-free test run of a
 * draft (#1010). Every method but {@link #test} is organization-scoped: a rule outside the caller's
 * organization is indistinguishable from a missing one ({@link SqlReviewCustomRuleNotFoundException}).
 * Every write publishes {@code SqlReviewCustomRuleChangedEvent}.
 */
public interface SqlReviewCustomRuleService {

    /** Upper bound on custom rules per organization. */
    int MAX_RULES_PER_ORGANIZATION = 50;

    /** Every custom rule of the organization, enabled or not, by rule id. */
    List<SqlReviewCustomRuleView> list(UUID organizationId);

    /** @throws SqlReviewCustomRuleNotFoundException when missing or in another organization */
    SqlReviewCustomRuleView get(UUID organizationId, UUID id);

    /**
     * @throws IllegalSqlReviewCustomRuleException  for a malformed rule or a full organization
     * @throws SqlReviewCustomRuleConflictException when the rule id is taken
     */
    SqlReviewCustomRuleView create(UUID organizationId, SqlReviewCustomRuleCommand command);

    /**
     * Replaces every field; the rule id is immutable.
     *
     * @throws SqlReviewCustomRuleNotFoundException when missing or in another organization
     * @throws IllegalSqlReviewCustomRuleException  for a malformed rule or a changed rule id
     */
    SqlReviewCustomRuleView update(UUID organizationId, UUID id, SqlReviewCustomRuleCommand command);

    /**
     * Deletes the rule and every ruleset config row naming it.
     *
     * @throws SqlReviewCustomRuleNotFoundException when missing or in another organization
     */
    void delete(UUID organizationId, UUID id);

    /**
     * Evaluates a draft rule at its default severity against {@code sql}, persisting, auditing and
     * publishing nothing. {@code dialect} {@code null} means PostgreSQL.
     *
     * @throws IllegalSqlReviewCustomRuleException for a malformed draft or a non-relational dialect
     */
    SqlReviewResult test(SqlReviewCustomRuleCommand draft, String sql, DbType dialect);
}
