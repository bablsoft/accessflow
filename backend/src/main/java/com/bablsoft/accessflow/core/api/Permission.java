package com.bablsoft.accessflow.core.api;

/**
 * The fixed, code-defined catalog of functional permissions (AF-522). Roles — the 5 immutable
 * system roles and admin-created custom roles — are composed from these values; admins cannot
 * invent new permissions. Each value maps to the Spring Security authority
 * {@code PERM_<name()>} minted from the JWT permissions claim.
 *
 * <p>Functional/application capabilities only: per-datasource access (can_read/can_write/can_ddl/
 * can_break_glass, allow-lists, masking, row security, JIT grants) and the orthogonal
 * {@code platform_admin} flag are separate systems and unaffected by this catalog. The
 * "never approve your own request" invariant is identity-based and holds regardless of
 * permissions.
 */
public enum Permission {

    /** Submit SELECT queries and use the personal query workflow (dashboard, own history). */
    QUERY_SUBMIT_SELECT(PermissionGroup.QUERIES),
    /** Submit INSERT/UPDATE/DELETE queries. */
    QUERY_SUBMIT_DML(PermissionGroup.QUERIES),
    /** Submit DDL queries. */
    QUERY_SUBMIT_DDL(PermissionGroup.QUERIES),
    /** View every query in the organization (not just own history), incl. CSV export scope. */
    QUERY_VIEW_ALL(PermissionGroup.QUERIES),
    /** Review queries: approve/reject/request changes, re-run AI analysis, review request groups. */
    QUERY_REVIEW(PermissionGroup.QUERIES),
    /** Reviewer-eligibility override: always an eligible approver, regardless of approver rules. */
    REVIEW_OVERRIDE(PermissionGroup.QUERIES),
    /**
     * Administrative request-workflow oversight: list/export any user's queries and API/grouped
     * requests, view any request's results, execute/replay any approved query, and submit/dry-run
     * against any datasource or API connector without a per-resource grant.
     */
    QUERY_ADMIN(PermissionGroup.QUERIES),

    /** Review (approve/reject) JIT datasource access requests. */
    ACCESS_REQUEST_REVIEW(PermissionGroup.ACCESS),
    /** Early-revoke an active JIT access grant. */
    ACCESS_GRANT_REVOKE(PermissionGroup.ACCESS),

    /** Manage API connectors: CRUD, schema ingestion, masking policies, classification tags. */
    API_CONNECTOR_MANAGE(PermissionGroup.API_GOVERNANCE),
    /** Review (approve/reject) governed API requests. */
    API_REQUEST_REVIEW(PermissionGroup.API_GOVERNANCE),

    /** Manage datasources: CRUD, connection tests, schema introspection, drivers, health. */
    DATASOURCE_MANAGE(PermissionGroup.DATASOURCES),
    /** Manage per-datasource user/group permissions and datasource reviewers. */
    DATASOURCE_PERMISSION_MANAGE(PermissionGroup.DATASOURCES),

    /** Manage column-masking policies. */
    MASKING_POLICY_MANAGE(PermissionGroup.DATA_POLICIES),
    /** Manage row-security policies. */
    ROW_SECURITY_MANAGE(PermissionGroup.DATA_POLICIES),
    /** Manage data-classification tags. */
    DATA_CLASSIFICATION_MANAGE(PermissionGroup.DATA_POLICIES),

    /** Manage review plans. */
    REVIEW_PLAN_MANAGE(PermissionGroup.WORKFLOW_ADMIN),
    /** Manage routing policies. */
    ROUTING_POLICY_MANAGE(PermissionGroup.WORKFLOW_ADMIN),
    /** View break-glass (emergency access) events. */
    BREAK_GLASS_VIEW(PermissionGroup.WORKFLOW_ADMIN),
    /** Acknowledge/retro-review break-glass events. */
    BREAK_GLASS_REVIEW(PermissionGroup.WORKFLOW_ADMIN),

    /** Manage data-retention policies. */
    RETENTION_POLICY_MANAGE(PermissionGroup.LIFECYCLE),
    /** Review (approve/reject) right-to-erasure requests. */
    ERASURE_REVIEW(PermissionGroup.LIFECYCLE),

    /** Manage attestation campaigns: create/open/cancel. */
    ATTESTATION_CAMPAIGN_MANAGE(PermissionGroup.ATTESTATION),
    /** Certify/revoke attestation items. */
    ATTESTATION_REVIEW(PermissionGroup.ATTESTATION),
    /** Export attestation evidence CSVs. */
    ATTESTATION_EVIDENCE_EXPORT(PermissionGroup.ATTESTATION),

    /** View compliance reports and the auditor dashboard. */
    COMPLIANCE_REPORT_VIEW(PermissionGroup.COMPLIANCE),
    /** View the audit log. */
    AUDIT_LOG_VIEW(PermissionGroup.COMPLIANCE),
    /** View behavioural anomalies. */
    ANOMALY_VIEW(PermissionGroup.COMPLIANCE),
    /** Manage behavioural anomalies (acknowledge/resolve) and detection settings. */
    ANOMALY_MANAGE(PermissionGroup.COMPLIANCE),

    /** Manage users: create, update, deactivate, invite. */
    USER_MANAGE(PermissionGroup.USERS),
    /** Manage user groups and memberships. */
    GROUP_MANAGE(PermissionGroup.USERS),
    /** Manage custom roles and view the permission catalog. */
    ROLE_MANAGE(PermissionGroup.USERS),

    /** Manage AI: provider configs, analyses history, knowledge base, Langfuse, RAG. */
    AI_MANAGE(PermissionGroup.AI),

    /** Manage notification channels and the Slack app config. */
    NOTIFICATION_CHANNEL_MANAGE(PermissionGroup.SETTINGS),
    /** Configure SAML and OAuth2/OIDC sign-in. */
    SSO_CONFIGURE(PermissionGroup.SETTINGS),
    /** Configure the system SMTP settings. */
    SMTP_CONFIGURE(PermissionGroup.SETTINGS),
    /** Configure localization (available languages). */
    LOCALIZATION_CONFIGURE(PermissionGroup.SETTINGS),
    /** View the admin setup-progress checklist. */
    SETUP_PROGRESS_VIEW(PermissionGroup.SETTINGS);

    private final PermissionGroup group;

    Permission(PermissionGroup group) {
        this.group = group;
    }

    public PermissionGroup group() {
        return group;
    }
}
