package com.bablsoft.accessflow.access.api;

/**
 * Lifecycle of an access-grant request. Mirrors the PostgreSQL {@code access_grant_status} enum.
 *
 * <pre>
 * PENDING ─┬─► APPROVED ─┬─► EXPIRED   (AccessGrantExpiryJob at expires_at ≤ now)
 *          │             └─► REVOKED   (admin early-revoke of an active grant)
 *          ├─► REJECTED              (reviewer rejection)
 *          └─► CANCELLED             (requester cancel, only while PENDING)
 * </pre>
 */
public enum AccessGrantStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    REVOKED,
    CANCELLED
}
