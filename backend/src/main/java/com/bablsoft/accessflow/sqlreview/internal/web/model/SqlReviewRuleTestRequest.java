package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.core.api.DbType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A draft custom rule and the SQL to run it against (#1010); {@code dialect} defaults to PostgreSQL. */
public record SqlReviewRuleTestRequest(
        @Valid
        @NotNull(message = "{validation.sql_review_custom_rule_test_rule.required}")
        SqlReviewCustomRuleRequest rule,

        @NotBlank(message = "{validation.sql.required}")
        @Size(max = 100_000, message = "{validation.sql.max}")
        String sql,

        DbType dialect
) {
}
