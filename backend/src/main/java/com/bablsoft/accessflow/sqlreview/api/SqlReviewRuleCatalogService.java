package com.bablsoft.accessflow.sqlreview.api;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The SQL review rule catalog of one organization, localized per reader (#863, #1009). */
public interface SqlReviewRuleCatalogService {

    /**
     * Every built-in rule in catalog order, with name and description rendered in {@code locale},
     * then the organization's enabled custom rules by rule id.
     */
    List<SqlReviewRuleView> rules(UUID organizationId, Locale locale);
}
