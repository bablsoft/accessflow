package com.bablsoft.accessflow.sqlreview.api;

import java.util.List;
import java.util.Locale;

/** The built-in SQL review rule catalog, localized per reader (#863). */
public interface SqlReviewRuleCatalogService {

    /** Every built-in rule in catalog order, with name and description rendered in {@code locale}. */
    List<SqlReviewRuleView> rules(Locale locale);
}
