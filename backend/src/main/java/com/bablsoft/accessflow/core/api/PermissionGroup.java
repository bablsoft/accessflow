package com.bablsoft.accessflow.core.api;

/**
 * Display grouping for the {@link Permission} catalog (AF-522). Groups drive the sectioning of the
 * admin permission-matrix UI and the {@code GET /admin/permissions} response; they carry no
 * enforcement semantics.
 */
public enum PermissionGroup {
    QUERIES,
    ACCESS,
    API_GOVERNANCE,
    DATASOURCES,
    DATA_POLICIES,
    WORKFLOW_ADMIN,
    LIFECYCLE,
    ATTESTATION,
    COMPLIANCE,
    USERS,
    AI,
    SETTINGS
}
