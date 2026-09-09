package com.bablsoft.accessflow.api.internal;

import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationSetupLookupService;
import com.bablsoft.accessflow.core.api.OrganizationView;
import com.bablsoft.accessflow.core.api.UpdateOrganizationCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGovernanceDomainsServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private final OrganizationSetupLookupService lookup = mock(OrganizationSetupLookupService.class);
    private final OrganizationAdminService adminService = mock(OrganizationAdminService.class);
    private final DefaultGovernanceDomainsService service =
            new DefaultGovernanceDomainsService(lookup, adminService);

    private OrganizationView view(boolean apis, boolean deployments) {
        return new OrganizationView(ORG, "Acme", "acme", false, null, null, null, apis,
                deployments, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void getReadsBothFlags() {
        when(lookup.governsApis(ORG)).thenReturn(true);
        when(lookup.governsDeployments(ORG)).thenReturn(false);

        assertThat(service.get(ORG)).isEqualTo(new GovernanceDomainsView(true, false));
    }

    @Test
    void updateWritesOnlyTheTwoFlagsAndReturnsThePersistedValues() {
        when(adminService.update(eq(ORG), any())).thenReturn(view(false, true));

        var result = service.update(ORG, false, true);

        assertThat(result).isEqualTo(new GovernanceDomainsView(false, true));
        var command = ArgumentCaptor.forClass(UpdateOrganizationCommand.class);
        verify(adminService).update(eq(ORG), command.capture());
        // Every quota/name field stays null so this path can never rename or re-quota a tenant.
        assertThat(command.getValue().name()).isNull();
        assertThat(command.getValue().maxDatasources()).isNull();
        assertThat(command.getValue().maxUsers()).isNull();
        assertThat(command.getValue().maxQueriesPerDay()).isNull();
        assertThat(command.getValue().governsApis()).isFalse();
        assertThat(command.getValue().governsDeployments()).isTrue();
    }
}
