package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Resolves the single deployment organization for code paths that have no JWT in scope
 * (the public OAuth2 providers endpoint and the dynamic ClientRegistrationRepository).
 * AccessFlow is single-tenant in practice; this service centralises the assumption so the
 * day a multi-org model ships, only this contract needs to change.
 */
public interface OrganizationLookupService {

    UUID singleOrganization();
}
