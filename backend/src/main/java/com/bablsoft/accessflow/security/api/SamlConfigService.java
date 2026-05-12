package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * Org-scoped Enterprise-only SAML/SSO configuration. {@link #getOrDefault} returns the persisted
 * row or a transient default snapshot (active=false, attribute defaults). {@link #update} performs
 * an upsert and applies the certificate-masking semantics described in
 * {@link UpdateSamlConfigCommand}.
 *
 * <p>Bean is only registered when {@code accessflow.edition=enterprise}; the controller is gated
 * the same way so Community builds receive 404 on {@code /admin/saml-config} routes.
 */
public interface SamlConfigService {

    SamlConfigView getOrDefault(UUID organizationId);

    SamlConfigView update(UUID organizationId, UpdateSamlConfigCommand command);
}
