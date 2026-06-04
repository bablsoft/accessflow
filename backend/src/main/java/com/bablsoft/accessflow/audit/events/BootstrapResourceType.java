package com.bablsoft.accessflow.audit.events;

/**
 * Discriminator for {@link BootstrapResourceUpsertedEvent}. Owned by the audit module so the
 * bootstrap module can publish to it without forming a cycle (audit consumes the event; bootstrap
 * imports the type from audit.events to fill it in). The listener maps each value to the
 * corresponding {@code AuditAction} / {@code AuditResourceType} internally.
 */
public enum BootstrapResourceType {
    ORGANIZATION,
    ADMIN_USER,
    NOTIFICATION_CHANNEL,
    AI_CONFIG,
    REVIEW_PLAN,
    DATASOURCE,
    SAML_CONFIG,
    OAUTH2_CONFIG,
    LANGFUSE_CONFIG,
    SYSTEM_SMTP
}
