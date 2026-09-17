package com.bablsoft.accessflow.core.api;

/**
 * Discriminator on {@code users.principal_type} (#868, epic #867): a person, or a non-human
 * identity (an MCP-connected agent, the Terraform provider, a CI job). A service account is still
 * a {@code users} row — every actor FK in the system points at {@code users(id)} — with a 1:1
 * detail row in {@code service_accounts} owned by the {@code serviceaccounts} module.
 */
public enum PrincipalType {
    HUMAN,
    SERVICE_ACCOUNT
}
