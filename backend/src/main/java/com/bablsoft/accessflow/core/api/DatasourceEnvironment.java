package com.bablsoft.accessflow.core.api;

/**
 * The environment a datasource serves (#861, epic #860). Optional on a datasource: an unset
 * environment is a legitimate state and resolves to the organization-wide default SQL review
 * ruleset. Fixed four-value set by design — environments are not org-defined.
 */
public enum DatasourceEnvironment {
    DEVELOPMENT,
    TEST,
    STAGING,
    PRODUCTION
}
