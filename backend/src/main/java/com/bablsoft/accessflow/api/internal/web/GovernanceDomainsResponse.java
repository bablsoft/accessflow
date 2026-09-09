package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.GovernanceDomainsView;

record GovernanceDomainsResponse(boolean governsApis, boolean governsDeployments) {

    static GovernanceDomainsResponse from(GovernanceDomainsView view) {
        return new GovernanceDomainsResponse(view.governsApis(), view.governsDeployments());
    }
}
