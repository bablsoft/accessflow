package com.bablsoft.accessflow.serviceaccounts.api;

import java.time.Instant;

/**
 * Input to {@link ServiceAccountAdminService#issueKey}; {@code expiresAt} null = non-expiring,
 * {@code applicationName} (#938) null = the key names no calling application.
 */
public record IssueServiceAccountKeyCommand(String name, Instant expiresAt, String applicationName) {

    public IssueServiceAccountKeyCommand(String name, Instant expiresAt) {
        this(name, expiresAt, null);
    }
}
