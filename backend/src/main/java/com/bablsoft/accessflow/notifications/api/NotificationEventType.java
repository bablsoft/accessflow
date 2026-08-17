package com.bablsoft.accessflow.notifications.api;

public enum NotificationEventType {
    QUERY_SUBMITTED,
    QUERY_APPROVED,
    QUERY_REJECTED,
    QUERY_ESCALATED,
    /** Recurring occurrence completed (#627) — result delivery to the submitter. */
    QUERY_EXECUTED,
    REVIEW_TIMEOUT,
    /**
     * #622: a request has sat in {@code PENDING_REVIEW} past its plan's
     * {@code escalation_after_hours}. Fired once per request, before the hard approval timeout.
     * Advisory — escalation never widens who may approve.
     */
    REVIEW_ESCALATED,
    /**
     * #622: a periodic reminder to reviewers who have not yet decided, on the plan's
     * {@code nudge_interval_hours} cadence.
     */
    REVIEW_NUDGE,
    AI_HIGH_RISK,
    ACCESS_REQUEST_SUBMITTED,
    ACCESS_REQUEST_APPROVED,
    ACCESS_REQUEST_REJECTED,
    ACCESS_GRANT_EXPIRED,
    ACCESS_GRANT_REVOKED,
    ANOMALY_DETECTED,
    /**
     * A standing grant crossed the staleness threshold (#625). Advisory nudge to org admins — never
     * pages, never opens a ticket, and never revokes anything.
     */
    GRANT_STALE,
    BREAK_GLASS_EXECUTED,
    WEEKLY_DIGEST,
    ATTESTATION_CAMPAIGN_OPENED,
    API_REQUEST_SUBMITTED,
    API_REQUEST_APPROVED,
    API_REQUEST_EXECUTED,
    API_REQUEST_FAILED,
    API_CONNECTOR_OAUTH2_TOKEN_FAILED,

    ERASURE_APPROVED,

    TEST
}
