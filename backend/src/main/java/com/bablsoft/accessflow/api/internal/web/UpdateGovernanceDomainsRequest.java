package com.bablsoft.accessflow.api.internal.web;

import jakarta.validation.constraints.NotNull;

/** Request body for the governance-domain toggles (#926). Both flags are always sent. */
record UpdateGovernanceDomainsRequest(
        @NotNull(message = "{validation.governance_domains.governs_apis_required}")
        Boolean governsApis,
        @NotNull(message = "{validation.governance_domains.governs_deployments_required}")
        Boolean governsDeployments) {
}
