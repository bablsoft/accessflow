package com.bablsoft.accessflow.audit.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the dedicated Postgres role that AccessFlow uses to INSERT into
 * {@code audit_log}. Bound to env vars {@code AUDIT_DB_USER} / {@code AUDIT_DB_PASSWORD}.
 * The JDBC URL is inherited from {@code spring.datasource.url} — the audit role lives in
 * the same Postgres instance as the application's general user.
 */
@ConfigurationProperties(prefix = "accessflow.audit.datasource")
public record AuditDataSourceProperties(String username, String password) {}
