package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.OrganizationSetupLookupService;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserSummaryFactoryTest {

    private static final UUID ORG = UUID.randomUUID();

    private final OrganizationSetupLookupService lookup = mock(OrganizationSetupLookupService.class);
    private final UserSummaryFactory factory = new UserSummaryFactory(
            (roleId, fallback) -> fallback != null ? SystemRolePermissions.of(fallback) : Set.of(),
            lookup);

    private UserView view(UserRoleType role) {
        return new UserView(UUID.randomUUID(), "u@example.com", "User", role, ORG, true,
                AuthProviderType.LOCAL, "hash", null, "de", false, Instant.now());
    }

    @Test
    void carriesResolvedPermissionsSortedAndTheOrganizationsGovernanceDomains() {
        when(lookup.governsApis(ORG)).thenReturn(true);
        when(lookup.governsDeployments(ORG)).thenReturn(false);

        var summary = factory.of(view(UserRoleType.ANALYST));

        assertThat(summary.email()).isEqualTo("u@example.com");
        assertThat(summary.role()).isEqualTo("ANALYST");
        assertThat(summary.preferredLanguage()).isEqualTo("de");
        assertThat(summary.authProvider()).isEqualTo("LOCAL");
        assertThat(summary.permissions())
                .containsExactly(SystemRolePermissions.of(UserRoleType.ANALYST).stream()
                        .map(Permission::name).sorted().toArray(String[]::new));
        assertThat(summary.governsApis()).isTrue();
        assertThat(summary.governsDeployments()).isFalse();
    }

    @Test
    void reportsBothDomainsWhenTheOrganizationGovernsThem() {
        when(lookup.governsApis(ORG)).thenReturn(true);
        when(lookup.governsDeployments(ORG)).thenReturn(true);

        var summary = factory.of(view(UserRoleType.ADMIN));

        assertThat(summary.governsApis()).isTrue();
        assertThat(summary.governsDeployments()).isTrue();
    }

    @Test
    void reportsNeitherDomainForADatabaseOnlyOrganization() {
        when(lookup.governsApis(ORG)).thenReturn(false);
        when(lookup.governsDeployments(ORG)).thenReturn(false);

        var summary = factory.of(view(UserRoleType.REVIEWER));

        assertThat(summary.governsApis()).isFalse();
        assertThat(summary.governsDeployments()).isFalse();
    }
}
