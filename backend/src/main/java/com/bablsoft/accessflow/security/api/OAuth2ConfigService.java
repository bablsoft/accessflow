package com.bablsoft.accessflow.security.api;

import java.util.List;
import java.util.UUID;

/**
 * Org-scoped OAuth2-provider configuration. {@link #getOrDefault} returns the persisted row
 * for the provider or a transient default snapshot (active=false, no client_id) so the admin
 * UI can render a form for every supported provider regardless of which ones have been saved.
 * {@link #update} performs an upsert and applies the secret-masking semantics described in
 * {@link UpdateOAuth2ConfigCommand}.
 */
public interface OAuth2ConfigService {

    List<OAuth2ConfigView> list(UUID organizationId);

    OAuth2ConfigView getOrDefault(UUID organizationId, OAuth2ProviderType provider);

    OAuth2ConfigView update(UUID organizationId, OAuth2ProviderType provider,
                            UpdateOAuth2ConfigCommand command);

    void delete(UUID organizationId, OAuth2ProviderType provider);

    List<OAuth2ProviderSummaryView> listActive(UUID organizationId);
}
