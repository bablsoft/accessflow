package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
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
        assertThatThrownBy(() -> reconciler.reconcile(new OrganizationSpec("  ", null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returnsExistingIdWhenSlugAlreadyExists() {
        var existingId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("acme")).thenReturn(Optional.of(existingId));

        var result = reconciler.reconcile(new OrganizationSpec("Acme", "acme"));

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

        var result = reconciler.reconcile(new OrganizationSpec("Acme", null));

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

        var result = reconciler.reconcile(new OrganizationSpec("Acme", "custom-slug"));

        assertThat(result).isEqualTo(newId);
    }

    @Test
    void slugifyFallsBackToDefaultWhenNameOnlyContainsPunctuation() {
        var newId = UUID.randomUUID();
        when(organizationProvisioningService.findBySlug("org")).thenReturn(Optional.empty());
        when(organizationProvisioningService.create("!!!", null)).thenReturn(newId);

        var result = reconciler.reconcile(new OrganizationSpec("!!!", null));

        assertThat(result).isEqualTo(newId);
    }
}
