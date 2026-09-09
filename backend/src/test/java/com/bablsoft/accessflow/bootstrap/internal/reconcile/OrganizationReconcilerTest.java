package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import com.bablsoft.accessflow.core.api.UpdateOrganizationCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationReconcilerTest {

    @Mock OrganizationProvisioningService organizationProvisioningService;
    @Mock OrganizationAdminService organizationAdminService;
    @Mock BootstrapStateTracker stateTracker;
    @Spy SpecFingerprinter fingerprinter = new SpecFingerprinter();
    @InjectMocks OrganizationReconciler reconciler;

    @Test
    void throwsWhenSpecIsNull() {
        assertThatThrownBy(() -> reconciler.reconcile(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void throwsWhenNameIsBlank() {
        assertThatThrownBy(() -> reconciler.reconcile(new OrganizationSpec("  ", null, null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returnsExistingIdWhenSlugAlreadyExists() {
        var existingId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("acme")).thenReturn(Optional.of(existingId));

        var result = reconciler.reconcile(new OrganizationSpec("Acme", "acme", null, null));

        assertThat(result).isEqualTo(existingId);
        verify(organizationProvisioningService, never()).create(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyString(), any());
    }

    @Test
    void createsWhenSlugMissing() {
        var newId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("acme")).thenReturn(Optional.empty());
        when(organizationProvisioningService.create("Acme", null)).thenReturn(newId);

        var result = reconciler.reconcile(new OrganizationSpec("Acme", null, null, null));

        assertThat(result).isEqualTo(newId);
        var captor = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(newId),
                eq(BootstrapResourceType.ORGANIZATION), eq(newId),
                org.mockito.ArgumentMatchers.anyString(),
                captor.capture());
        var event = captor.getValue();
        assertThat(event.resourceType()).isEqualTo(BootstrapResourceType.ORGANIZATION);
        assertThat(event.resourceId()).isEqualTo(newId);
        assertThat(event.changeKind()).isEqualTo(BootstrapChangeKind.CREATE);
        assertThat(event.summaryMetadata()).containsEntry("name", "Acme").containsEntry("slug", "acme");
    }

    @Test
    void usesProvidedSlugForLookupWhenPresent() {
        var newId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("custom-slug")).thenReturn(Optional.empty());
        when(organizationProvisioningService.create("Acme", "custom-slug")).thenReturn(newId);

        var result = reconciler.reconcile(new OrganizationSpec("Acme", "custom-slug", null, null));

        assertThat(result).isEqualTo(newId);
    }

    @Test
    void slugifyFallsBackToDefaultWhenNameOnlyContainsPunctuation() {
        var newId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("org")).thenReturn(Optional.empty());
        when(organizationProvisioningService.create("!!!", null)).thenReturn(newId);

        var result = reconciler.reconcile(new OrganizationSpec("!!!", null, null, null));

        assertThat(result).isEqualTo(newId);
    }

    @Test
    void appliesGovernanceDomainsToANewlyCreatedOrganization() {
        var orgId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("acme")).thenReturn(Optional.empty());
        when(organizationProvisioningService.create("Acme", "acme")).thenReturn(orgId);

        reconciler.reconcile(new OrganizationSpec("Acme", "acme", true, false));

        var command = ArgumentCaptor.forClass(UpdateOrganizationCommand.class);
        verify(organizationAdminService).update(eq(orgId), command.capture());
        assertThat(command.getValue().governsApis()).isTrue();
        assertThat(command.getValue().governsDeployments()).isFalse();
        assertThat(command.getValue().name()).isNull();
        assertThat(command.getValue().maxUsers()).isNull();
    }

    @Test
    void appliesGovernanceDomainsToAnExistingOrganizationWhoseDeclaredSpecChanged() {
        var existingId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("acme")).thenReturn(Optional.of(existingId));
        when(stateTracker.findFingerprint(existingId, BootstrapResourceType.ORGANIZATION, existingId))
                .thenReturn(Optional.of("a-stale-fingerprint"));

        var result = reconciler.reconcile(new OrganizationSpec("Acme", "acme", true, true));

        assertThat(result).isEqualTo(existingId);
        var command = ArgumentCaptor.forClass(UpdateOrganizationCommand.class);
        verify(organizationAdminService).update(eq(existingId), command.capture());
        assertThat(command.getValue().governsDeployments()).isTrue();
        // The new fingerprint is recorded with an UPDATE event, like every other reconciler.
        verify(stateTracker).recordFingerprintAndPublish(eq(existingId),
                eq(BootstrapResourceType.ORGANIZATION), eq(existingId), any(), any());
    }

    @Test
    void leavesAnExistingOrganizationAloneWhenTheDeclaredSpecIsUnchanged() {
        var existingId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("acme")).thenReturn(Optional.of(existingId));
        // The fingerprint the previous run stored for exactly this spec.
        var spec = new OrganizationSpec("Acme", "acme", true, true);
        var stored = fingerprinter.fingerprint(new java.util.LinkedHashMap<>(java.util.Map.of(
                "name", "Acme", "slug", "acme", "governs_apis", true, "governs_deployments", true)));
        when(stateTracker.findFingerprint(existingId, BootstrapResourceType.ORGANIZATION, existingId))
                .thenReturn(Optional.of(stored));

        reconciler.reconcile(spec);

        // No write, so a restart cannot revert what an admin just changed through
        // PUT /admin/governance-domains, nor churn organizations.updated_at.
        verify(organizationAdminService, never()).update(any(), any());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(), any(), any());
    }

    @Test
    void leavesGovernanceDomainsAloneWhenTheSpecSetsNeither() {
        var existingId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("acme")).thenReturn(Optional.of(existingId));

        reconciler.reconcile(new OrganizationSpec("Acme", "acme", null, null));

        verify(organizationAdminService, never()).update(any(), any());
    }
}
