package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.ServiceAccountSpec;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
class ServiceAccountReconcilerTest {

    @Mock UserQueryService userQueryService;
    @Mock UserAdminService userAdminService;
    @Mock ApiKeyService apiKeyService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock BootstrapStateTracker stateTracker;
    @Spy SpecFingerprinter fingerprinter = new SpecFingerprinter();
    @InjectMocks ServiceAccountReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void emptyListReturnsEmptyMap() {
        assertThat(reconciler.reconcile(ORG_ID, List.of())).isEmpty();
    }

    @Test
    void createsUserWithUnusablePasswordAndImportsKeyWhenUserAbsent() {
        var newUserId = UUID.randomUUID();
        when(userQueryService.findByEmail("ci@acme.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("ENCODED");
        when(userAdminService.createUser(any(CreateUserCommand.class)))
                .thenReturn(user(newUserId, "ci@acme.com", UserRoleType.ADMIN));

        var spec = new ServiceAccountSpec("ci@acme.com", "CI", null, "terraform", "af_raw_key", null);
        var result = reconciler.reconcile(ORG_ID, List.of(spec));

        assertThat(result).containsEntry("ci@acme.com", newUserId);

        var cmd = ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userAdminService).createUser(cmd.capture());
        assertThat(cmd.getValue().role()).isEqualTo(UserRoleType.ADMIN); // default role
        assertThat(cmd.getValue().platformAdmin()).isFalse();
        assertThat(cmd.getValue().passwordHash()).isEqualTo("ENCODED");
        assertThat(cmd.getValue().passwordHash()).isNotEqualTo("af_raw_key");

        verify(apiKeyService).importOrUpdate(newUserId, ORG_ID, "terraform", "af_raw_key", null);

        var event = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.SERVICE_ACCOUNT), eq(newUserId),
                org.mockito.ArgumentMatchers.anyString(), event.capture());
        assertThat(event.getValue().changeKind()).isEqualTo(BootstrapChangeKind.CREATE);
        assertThat(event.getValue().summaryMetadata())
                .containsEntry("email", "ci@acme.com")
                .containsEntry("api_key_name", "terraform")
                .containsEntry("role", "ADMIN");
        // The raw key is never leaked into audit metadata.
        assertThat(event.getValue().summaryMetadata()).doesNotContainValue("af_raw_key");
    }

    @Test
    void honoursExplicitRole() {
        var newUserId = UUID.randomUUID();
        when(userQueryService.findByEmail("ci@acme.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("ENCODED");
        when(userAdminService.createUser(any(CreateUserCommand.class)))
                .thenReturn(user(newUserId, "ci@acme.com", UserRoleType.REVIEWER));

        reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec("ci@acme.com", "CI", UserRoleType.REVIEWER, "tf", "af_k", null)));

        var cmd = ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userAdminService).createUser(cmd.capture());
        assertThat(cmd.getValue().role()).isEqualTo(UserRoleType.REVIEWER);
    }

    @Test
    void reusesExistingUserAndUpdatesKeyWhenFingerprintDiffers() {
        var existingId = UUID.randomUUID();
        when(userQueryService.findByEmail("ci@acme.com"))
                .thenReturn(Optional.of(user(existingId, "ci@acme.com", UserRoleType.ADMIN)));
        when(stateTracker.findFingerprint(ORG_ID, BootstrapResourceType.SERVICE_ACCOUNT, existingId))
                .thenReturn(Optional.of("stale-fingerprint"));

        reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec("ci@acme.com", "CI", null, "terraform", "af_rotated", null)));

        verify(userAdminService, never()).createUser(any());
        verify(apiKeyService).importOrUpdate(existingId, ORG_ID, "terraform", "af_rotated", null);

        var event = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.SERVICE_ACCOUNT), eq(existingId),
                org.mockito.ArgumentMatchers.anyString(), event.capture());
        assertThat(event.getValue().changeKind()).isEqualTo(BootstrapChangeKind.UPDATE);
    }

    @Test
    void skipsKeyImportWhenFingerprintMatches() {
        var existingId = UUID.randomUUID();
        when(userQueryService.findByEmail("ci@acme.com"))
                .thenReturn(Optional.of(user(existingId, "ci@acme.com", UserRoleType.ADMIN)));
        when(fingerprinter.fingerprint(any())).thenReturn("fp");
        when(stateTracker.findFingerprint(ORG_ID, BootstrapResourceType.SERVICE_ACCOUNT, existingId))
                .thenReturn(Optional.of("fp"));

        reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec("ci@acme.com", "CI", null, "terraform", "af_key", null)));

        verify(apiKeyService, never()).importOrUpdate(any(), any(), any(), any(), any());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(), any(), any());
    }

    @Test
    void throwsWhenExistingUserBelongsToAnotherOrganization() {
        var otherOrgUser = new UserView(UUID.randomUUID(), "ci@acme.com", "CI", UserRoleType.ADMIN,
                UUID.randomUUID(), true, AuthProviderType.LOCAL, "h", null, "en", false, Instant.now());
        when(userQueryService.findByEmail("ci@acme.com")).thenReturn(Optional.of(otherOrgUser));

        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec("ci@acme.com", "CI", null, "tf", "af_k", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different organization");
    }

    @Test
    void throwsWhenEmailMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec(" ", "CI", null, "tf", "af_k", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email");
    }

    @Test
    void throwsWhenDisplayNameMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec("ci@acme.com", null, null, "tf", "af_k", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void throwsWhenApiKeyNameMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec("ci@acme.com", "CI", null, "", "af_k", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiKeyName");
    }

    @Test
    void throwsWhenApiKeyMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new ServiceAccountSpec("ci@acme.com", "CI", null, "tf", null, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiKey");
    }

    private UserView user(UUID id, String email, UserRoleType role) {
        return new UserView(id, email, "CI", role, ORG_ID, true, AuthProviderType.LOCAL,
                "hash", null, "en", false, Instant.now());
    }
}
