package com.bablsoft.accessflow.audit.api;

/**
 * Catalog of audit actions written to {@code audit_log.action}. Mirrors the table in
 * {@code docs/03-data-model.md}; {@link #QUERY_AI_FAILED} is an extension that lets the read API
 * filter for failed AI runs without parsing the JSONB metadata.
 */
public enum AuditAction {
    QUERY_SUBMITTED,
    QUERY_AI_ANALYZED,
    QUERY_AI_FAILED,
    QUERY_REVIEW_REQUESTED,
    QUERY_APPROVED,
    QUERY_REJECTED,
    QUERY_EXECUTED,
    QUERY_FAILED,
    QUERY_CANCELLED,
    DATASOURCE_CREATED,
    DATASOURCE_UPDATED,
    PERMISSION_GRANTED,
    PERMISSION_REVOKED,
    REVIEW_PLAN_CREATED,
    REVIEW_PLAN_UPDATED,
    REVIEW_PLAN_DELETED,
    USER_LOGIN,
    USER_LOGIN_FAILED,
    USER_LOGIN_TOTP_FAILED,
    USER_CREATED,
    USER_DEACTIVATED,
    USER_PROFILE_UPDATED,
    USER_PASSWORD_CHANGED,
    USER_TOTP_ENABLED,
    USER_TOTP_DISABLED,
    AI_CONFIG_CREATED,
    AI_CONFIG_UPDATED,
    AI_CONFIG_DELETED
}
