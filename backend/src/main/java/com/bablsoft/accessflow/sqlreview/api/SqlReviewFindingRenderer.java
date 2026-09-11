package com.bablsoft.accessflow.sqlreview.api;

import java.util.Locale;

/**
 * Renders a finding's human-readable message per reader (#863). A finding is stored as
 * {@code rule_id} + {@code args} and never as text; this is the one place that binds the args, in
 * the rule's declared order, onto the positional placeholders of
 * {@code sqlreview.rule.<rule_id>.message}. Shared by the evaluation endpoint, the query detail and
 * the reviewer queue.
 */
public interface SqlReviewFindingRenderer {

    String message(SqlReviewFinding finding, Locale locale);
}
