package com.bablsoft.accessflow.serviceaccounts.api;

import java.time.Instant;

/** Input to {@link ServiceAccountAdminService#issueKey}; {@code expiresAt} null = non-expiring. */
public record IssueServiceAccountKeyCommand(String name, Instant expiresAt) {
}
