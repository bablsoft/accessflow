package com.bablsoft.accessflow.audit.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit-module configuration. {@code hmacKey} is the hex-encoded HMAC-SHA256 key used to chain
 * rows in {@code audit_log}. Bound to env var {@code AUDIT_HMAC_KEY}; if unset, the audit
 * configuration derives a key from {@code accessflow.encryption-key} for community installs and
 * fails startup otherwise.
 */
@ConfigurationProperties(prefix = "accessflow.audit")
public record AuditHmacProperties(String hmacKey) {}
