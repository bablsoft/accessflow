package com.bablsoft.accessflow.security.internal.saml.events;

import java.util.UUID;

/**
 * Published by {@code DefaultSamlConfigService.update} whenever an admin saves a SAML config
 * change. Listeners (notably {@code DynamicRelyingPartyRegistrationRepository}) evict their cache
 * so the next request reads fresh state — no app restart required.
 */
public record SamlConfigUpdatedEvent(UUID organizationId) {
}
