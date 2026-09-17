package com.bablsoft.accessflow.serviceaccounts.api;

/**
 * Which surface owns a service account's declared fields ({@code service_accounts.managed_by},
 * #868). A {@code BOOTSTRAP} account is declared in the reconciler's YAML, which re-asserts its
 * email, display name, role and declared API key on every restart; a {@code UI} account is edited
 * from the admin pages only (#871). Fields the bootstrap spec does not carry — description, owner,
 * tool allow-list, rate limits — are UI-owned on both.
 */
public enum ServiceAccountSource {
    UI,
    BOOTSTRAP
}
