package com.bablsoft.accessflow.audit.api;

/**
 * Destination types for external audit-log streaming (#628). Mirrors the PostgreSQL
 * {@code audit_sink_type} enum.
 */
public enum AuditSinkType {
    /** Splunk HTTP Event Collector: newline-stacked HEC envelopes per batch. */
    SPLUNK_HEC,
    /** Syslog over TCP or TLS carrying CEF:0 messages with RFC 6587 octet-counting framing. */
    SYSLOG_CEF,
    /** Generic HTTPS receiver: JSON array batches signed with an HMAC-SHA256 header. */
    HTTPS_BATCH,
    /** Periodic signed JSONL segments uploaded to S3 under an Object Lock (WORM) retention. */
    S3_OBJECT_LOCK
}
