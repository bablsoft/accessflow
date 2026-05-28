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
                fixedSpec(OAuth2ProviderType.GOOGLE, "g-id", "g-sec"),
                fixedSpec(OAuth2ProviderType.GITHUB, "gh-id", "gh-sec")));

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

        reconciler.reconcile(ORG_ID, List.of(fixedSpec(OAuth2ProviderType.GOOGLE, "g-id", "g-sec")));

        verify(oauth2ConfigService, never()).update(any(), any(), any());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(), any(), any());
    }

    @Test
    void publishesEventCarryingProviderMetadata() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE)))
                .thenReturn(defaultView(OAuth2ProviderType.GOOGLE));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(fixedSpec(OAuth2ProviderType.GOOGLE, "g-id", "g-sec")));

        var eventCaptor = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.OAUTH2_CONFIG),
                eq(OAuth2Reconciler.providerResourceId(OAuth2ProviderType.GOOGLE.name())),
                org.mockito.ArgumentMatchers.anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().resourceType()).isEqualTo(BootstrapResourceType.OAUTH2_CONFIG);
        assertThat(eventCaptor.getValue().summaryMetadata()).containsEntry("provider", "GOOGLE");
    }

    @Test
    void roundTripsAllowlists() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB)))
                .thenReturn(defaultView(OAuth2ProviderType.GITHUB));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(new OAuth2Spec(
                OAuth2ProviderType.GITHUB, "gh-id", "gh-sec",
                "read:user user:email read:org", null,
                null, null, null, null, null, null, null, null, null, null, null,
                null,
                List.of("bablsoft"), List.of("example.com"),
                null,
                UserRoleType.ANALYST, true)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB), captor.capture());
        assertThat(captor.getValue().allowedOrganizations()).containsExactly("bablsoft");
        assertThat(captor.getValue().allowedEmailDomains()).containsExactly("example.com");
    }

    @Test
    void throwsWhenProviderMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(null, "x", "y", null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void throwsWhenClientIdMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                fixedSpec(OAuth2ProviderType.GOOGLE, " ", "secret"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void defaultsActiveToTrueWhenSpecActiveNull() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE)))
                .thenReturn(defaultView(OAuth2ProviderType.GOOGLE));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(new OAuth2Spec(
                OAuth2ProviderType.GOOGLE, "id", "sec", null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null,
                null, null, null, null, null)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE), captor.capture());
        assertThat(captor.getValue().active()).isTrue();
    }

    @Test
    void appliesOidcSpec() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.OIDC)))
                .thenReturn(defaultView(OAuth2ProviderType.OIDC));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.OIDC),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(oidcSpec()));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.OIDC), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.displayName()).isEqualTo("Mock IdP");
        assertThat(cmd.authorizationUri()).isEqualTo("http://idp/authorize");
        assertThat(cmd.tokenUri()).isEqualTo("http://idp/token");
        assertThat(cmd.userInfoUri()).isEqualTo("http://idp/userinfo");
        assertThat(cmd.jwkSetUri()).isEqualTo("http://idp/jwks");
        assertThat(cmd.issuerUri()).isEqualTo("http://idp");
        assertThat(cmd.groupsAttribute()).isEqualTo("groups");
    }

    @Test
    void rejectsOidcSpecMissingDisplayName() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(new OAuth2Spec(
                OAuth2ProviderType.OIDC, "c", "s", null, null,
                null, "http://idp/authorize", "http://idp/token",
                "http://idp/userinfo", "http://idp/jwks", "http://idp",
                null, null, null, null, null, null, null, null, null, UserRoleType.ANALYST, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void rejectsGithubEnterpriseSpecMissingBaseUrl() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(new OAuth2Spec(
                OAuth2ProviderType.GITHUB_ENTERPRISE, "c", "s", null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null,
                null, null, null, UserRoleType.ANALYST, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void rejectsGitlabEnterpriseSpecMissingBaseUrl() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(new OAuth2Spec(
                OAuth2ProviderType.GITLAB_ENTERPRISE, "c", "s", null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null,
                null, null, null, UserRoleType.ANALYST, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void reconcilesGithubEnterpriseSpecAndPlumbsBaseUrl() {
        when(oauth2ConfigService.getOrDefault(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB_ENTERPRISE)))
                .thenReturn(defaultView(OAuth2ProviderType.GITHUB_ENTERPRISE));
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB_ENTERPRISE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(new OAuth2Spec(
                OAuth2ProviderType.GITHUB_ENTERPRISE, "gh-id", "gh-sec", null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                "https://gh.acme.corp",
                null, null, null, UserRoleType.ANALYST, true)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB_ENTERPRISE), captor.capture());
        assertThat(captor.getValue().baseUrl()).isEqualTo("https://gh.acme.corp");
    }

    @Test
    void rejectsOidcSpecMissingTokenUri() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(new OAuth2Spec(
                OAuth2ProviderType.OIDC, "c", "s", null, null,
                "Mock", "http://idp/authorize", null,
                "http://idp/userinfo", "http://idp/jwks", "http://idp",
                null, null, null, null, null, null, null, null, null, UserRoleType.ANALYST, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tokenUri");
    }

    private static OAuth2ConfigView defaultView(OAuth2ProviderType provider) {
        return new OAuth2ConfigView(UUID.randomUUID(), ORG_ID, provider, null, false, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), java.util.Map.of(),
                UserRoleType.REVIEWER, false, Instant.now(), Instant.now());
    }

    private static OAuth2Spec fixedSpec(OAuth2ProviderType provider, String clientId, String clientSecret) {
        return new OAuth2Spec(
                provider, clientId, clientSecret, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, UserRoleType.REVIEWER, true);
    }

    private static OAuth2Spec oidcSpec() {
        return new OAuth2Spec(
                OAuth2ProviderType.OIDC, "c", "s", null, null,
                "Mock IdP",
                "http://idp/authorize",
                "http://idp/token",
                "http://idp/userinfo",
                "http://idp/jwks",
                "http://idp",
                "sub", "email", "email_verified", "name", "groups",
                null,
                null, null, null, UserRoleType.ANALYST, true);
    }
}
