package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.SamlSpec;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.SamlConfigService;
import com.bablsoft.accessflow.security.api.SamlConfigView;
import com.bablsoft.accessflow.security.api.UpdateSamlConfigCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlReconcilerTest {

    @Mock SamlConfigService samlConfigService;
    @Mock BootstrapStateTracker stateTracker;
    @Spy SpecFingerprinter fingerprinter = new SpecFingerprinter();
    @InjectMocks SamlReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void skipsWhenSpecNull() {
        reconciler.reconcile(ORG_ID, null);
        verify(samlConfigService, never()).update(any(), any());
    }

    @Test
    void skipsWhenDisabled() {
        reconciler.reconcile(ORG_ID, new SamlSpec(false, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        verify(samlConfigService, never()).update(any(), any());
    }

    @Test
    void appliesUpdateWhenEnabled() {
        when(samlConfigService.getOrDefault(ORG_ID)).thenReturn(defaultView());
        when(samlConfigService.update(eq(ORG_ID), any(UpdateSamlConfigCommand.class)))
                .thenAnswer(inv -> null);

        var spec = new SamlSpec(true, "https://idp/meta", "idp", "sp", "https://sp/acs",
                null, "cert", "email", "name", "role", null, null, UserRoleType.REVIEWER, true);

        reconciler.reconcile(ORG_ID, spec);

        var captor = ArgumentCaptor.forClass(UpdateSamlConfigCommand.class);
        verify(samlConfigService).update(eq(ORG_ID), captor.capture());
        assertThat(captor.getValue().idpMetadataUrl()).isEqualTo("https://idp/meta");
        assertThat(captor.getValue().signingCertPem()).isEqualTo("cert");
        assertThat(captor.getValue().defaultRole()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(captor.getValue().active()).isTrue();

        var eventCaptor = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.SAML_CONFIG), eq(ORG_ID),
                org.mockito.ArgumentMatchers.anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().resourceType()).isEqualTo(BootstrapResourceType.SAML_CONFIG);
        assertThat(eventCaptor.getValue().resourceId()).isEqualTo(ORG_ID);
    }

    @Test
    void skipsUpdateAndEventWhenFingerprintMatches() {
        when(fingerprinter.fingerprint(any())).thenReturn("matching-fp");
        when(stateTracker.findFingerprint(ORG_ID, BootstrapResourceType.SAML_CONFIG, ORG_ID))
                .thenReturn(Optional.of("matching-fp"));

        var spec = new SamlSpec(true, "https://idp", "idp", "sp", "https://sp/acs",
                null, null, null, null, null, null, null, null, null);

        reconciler.reconcile(ORG_ID, spec);

        verify(samlConfigService, never()).update(any(), any());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(), any(), any());
    }

    private static SamlConfigView defaultView() {
        return new SamlConfigView(UUID.randomUUID(), ORG_ID, null, null, null, null, null, false,
                "email", "displayName", "role", null, java.util.Map.of(),
                UserRoleType.REVIEWER, false, Instant.now(), Instant.now());
    }

    @Test
    void defaultsActiveToTrueWhenSpecActiveNull() {
        var spec = new SamlSpec(true, "https://idp", "idp", "sp", "https://sp/acs",
                null, null, null, null, null, null, null, null, null);
        when(samlConfigService.getOrDefault(ORG_ID)).thenReturn(defaultView());
        when(samlConfigService.update(eq(ORG_ID), any(UpdateSamlConfigCommand.class)))
                .thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, spec);

        var captor = ArgumentCaptor.forClass(UpdateSamlConfigCommand.class);
        verify(samlConfigService).update(eq(ORG_ID), captor.capture());
        assertThat(captor.getValue().active()).isTrue();
    }
}
