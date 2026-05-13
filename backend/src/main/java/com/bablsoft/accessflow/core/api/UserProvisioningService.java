package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * JIT user provisioning for external sign-in flows (OAuth2 today, SAML when it lands).
 * Implementations match by email and never auto-link an external login to an existing LOCAL
 * account with a password hash — that combination throws {@link ExternalLocalAccountConflictException}
 * so the admin must intervene. Without that guard, anyone controlling a provider account with the
 * same email could take over a local account.
 */
public interface UserProvisioningService {

    UserView findOrProvision(UUID organizationId,
                             String email,
                             String displayName,
                             AuthProviderType authProvider,
                             UserRoleType defaultRole);
}
