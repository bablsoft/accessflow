package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Cross-org organization management (AF-456). Unlike every other admin service, these operations are
 * NOT scoped to a caller's organization — they act on any organization by id and are reachable only
 * by a platform admin (the {@code PLATFORM_ADMIN} authority), enforced at the web layer.
 */
public interface OrganizationAdminService {

    PageResponse<OrganizationView> list(PageRequest pageRequest);

    /** Throws {@link OrganizationNotFoundException} when no organization has the given id. */
    OrganizationView get(UUID organizationId);

    OrganizationView create(CreateOrganizationCommand command);

    /** Applies non-null fields; throws {@link OrganizationNotFoundException} when missing. */
    OrganizationView update(UUID organizationId, UpdateOrganizationCommand command);

    /** Enables or disables a whole tenant; throws {@link OrganizationNotFoundException} when missing. */
    OrganizationView setDisabled(UUID organizationId, boolean disabled);

    /** Current resource usage vs. quotas; throws {@link OrganizationNotFoundException} when missing. */
    OrganizationUsageView getUsage(UUID organizationId);
}
