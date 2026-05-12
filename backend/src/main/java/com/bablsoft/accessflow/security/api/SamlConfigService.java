package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * Org-scoped SAML/SSO configuration. {@link #getOrDefault} returns the persisted row or a transient
 * default snapshot (active=false, attribute defaults). {@link #update} performs an upsert and
 * applies the certificate-masking semantics described in {@link UpdateSamlConfigCommand}.
 */
public interface SamlConfigService {

    SamlConfigView getOrDefault(UUID organizationId);

    SamlConfigView update(UUID organizationId, UpdateSamlConfigCommand command);
}
