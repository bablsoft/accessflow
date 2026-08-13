package com.bablsoft.accessflow.scim.api;

import java.util.UUID;

/** Per-organization SCIM 2.0 provisioning settings (#621), a singleton row per org. */
public interface ScimConfigService {

    /** The org's configuration, or an all-default (disabled) view when none was saved yet. */
    ScimConfigView get(UUID organizationId);

    /**
     * Partial update / upsert.
     *
     * @throws ScimInvalidMappingException when an attribute-mapping value is not allowed
     */
    ScimConfigView update(UUID organizationId, UpdateScimConfigCommand command);

    /** Whether SCIM provisioning is enabled for the org — checked on every SCIM request. */
    boolean isEnabled(UUID organizationId);
}
