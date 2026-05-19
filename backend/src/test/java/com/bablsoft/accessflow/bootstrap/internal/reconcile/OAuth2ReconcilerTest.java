package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.OAuth2Spec;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.OAuth2ConfigView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
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
class OAuth2ReconcilerTest {

    @Mock OAuth2ConfigService oauth2ConfigService;
    @Mock BootstrapStateTracker stateTracker;
    @Spy SpecFingerprinter fingerprinter = new SpecFingerprinter();
    @InjectMocks OAuth2Reconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void appliesEachProvider() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), any(OAuth2ProviderType.class)))
                .thenAnswer(inv -> defaultView(inv.getArgument(1)));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, "g-id", "g-sec", null, null,
                        null, null, UserRoleType.REVIEWER, true),
                new OAuth2Spec(OAuth2ProviderType.GITHUB, "gh-id", "gh-sec", null, null,
                        null, null, UserRoleType.REVIEWER, true)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE), captor.capture());
        assertThat(captor.getValue().clientId()).isEqualTo("g-id");
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB),
                any(UpdateOAuth2ConfigCommand.class));
    }

    @Test
    void skipsUpdateAndEventWhenFingerprintMatches() {
        when(fingerprinter.fingerprint(any())).thenReturn("matching-fp");
        when(stateTracker.findFingerprint(eq(ORG_ID), eq(BootstrapResourceType.OAUTH2_CONFIG), any(UUID.class)))
                .thenReturn(Optional.of("matching-fp"));

        reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, "g-id", "g-sec", null, null,
                        null, null, UserRoleType.REVIEWER, true)));

        verify(oauth2ConfigService, never()).update(any(), any(), any());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(), any(), any());
    }

    @Test
    void publishesEventCarryingProviderMetadata() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE)))
                .thenReturn(defaultView(OAuth2ProviderType.GOOGLE));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, "g-id", "g-sec", null, null,
                        null, null, UserRoleType.REVIEWER, true)));

        var eventCaptor = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.OAUTH2_CONFIG),
                eq(OAuth2Reconciler.providerResourceId(OAuth2ProviderType.GOOGLE.name())),
                org.mockito.ArgumentMatchers.anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().resourceType()).isEqualTo(BootstrapResourceType.OAUTH2_CONFIG);
        assertThat(eventCaptor.getValue().summaryMetadata()).containsEntry("provider", "GOOGLE");
    }

    private static OAuth2ConfigView defaultView(OAuth2ProviderType provider) {
        return new OAuth2ConfigView(UUID.randomUUID(), ORG_ID, provider, null, false, null, null,
                List.of(), List.of(), UserRoleType.REVIEWER, false, Instant.now(), Instant.now());
    }

    @Test
    void roundTripsAllowlists() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB)))
                .thenReturn(defaultView(OAuth2ProviderType.GITHUB));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GITHUB, "gh-id", "gh-sec",
                        "read:user user:email read:org", null,
                        List.of("bablsoft"), List.of("example.com"),
                        UserRoleType.ANALYST, true)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB), captor.capture());
        assertThat(captor.getValue().allowedOrganizations()).containsExactly("bablsoft");
        assertThat(captor.getValue().allowedEmailDomains()).containsExactly("example.com");
    }

    @Test
    void throwsWhenProviderMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(null, "x", "y", null, null, null, null, null, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void throwsWhenClientIdMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, " ", "secret", null, null,
                        null, null, null, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void defaultsActiveToTrueWhenSpecActiveNull() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE)))
                .thenReturn(defaultView(OAuth2ProviderType.GOOGLE));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, "id", "sec", null, null,
                        null, null, null, null)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE), captor.capture());
        assertThat(captor.getValue().active()).isTrue();
    }
}
