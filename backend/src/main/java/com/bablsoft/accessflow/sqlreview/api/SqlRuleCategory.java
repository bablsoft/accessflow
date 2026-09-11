package com.bablsoft.accessflow.sqlreview.api;

/**
 * The family a built-in SQL review rule belongs to (#862). Purely descriptive — it groups the
 * catalog for admins and never influences evaluation or severity.
 */
public enum SqlRuleCategory {
    /** Statements that can silently touch every row, or defeat the guards that would stop them. */
    STATEMENT_SAFETY,
    /** Constructs that force full scans or unbounded result sets. */
    PERFORMANCE,
    /** DDL — anything that changes the shape of the schema. */
    SCHEMA_CHANGE,
    /** Access to tables an organisation has declared protected. */
    DATA_PROTECTION
}
