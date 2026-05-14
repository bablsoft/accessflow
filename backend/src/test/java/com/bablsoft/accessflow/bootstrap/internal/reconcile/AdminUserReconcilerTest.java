package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.AdminSpec;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserReconcilerTest {

    @Mock UserQueryService userQueryService;
    @Mock UserAdminService userAdminService;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks AdminUserReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void throwsWhenEmailIsBlank() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID,
                new AdminSpec(" ", "Admin", "pw")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email");
    }

    @Test
    void throwsWhenDisplayNameMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID,
                new AdminSpec("x@y.z", null, "pw")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void throwsWhenPasswordMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID,
                new AdminSpec("x@y.z", "X", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password");
    }

    @Test
    void createsUserWhenEmailNotFound() {
        var newId = UUID.randomUUID();
        when(userQueryService.findByEmail("admin@acme.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("s3cret")).thenReturn("hashed");
        when(userAdminService.createUser(any(CreateUserCommand.class)))
                .thenAnswer(inv -> {
                    CreateUserCommand cmd = inv.getArgument(0);
                    return new UserView(newId, cmd.email(), cmd.displayName(), cmd.role(),
                            cmd.organizationId(), true, AuthProviderType.LOCAL,
                            cmd.passwordHash(), null, null, false, null);
                });

        var id = reconciler.reconcile(ORG_ID, new AdminSpec("admin@acme.com", "Acme Admin", "s3cret"));

        assertThat(id).isEqualTo(newId);

        var captor = ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userAdminService).createUser(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(UserRoleType.ADMIN);
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG_ID);
        assertThat(captor.getValue().passwordHash()).isEqualTo("hashed");
    }

    @Test
    void skipsRotationWhenAdminExistsAndPasswordMatches() {
        var existingId = UUID.randomUUID();
        var existing = new UserView(existingId, "admin@acme.com", "X", UserRoleType.ADMIN,
                ORG_ID, true, AuthProviderType.LOCAL, "stored-hash", null, null, false, null);
        when(userQueryService.findByEmail("admin@acme.com")).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches("s3cret", "stored-hash")).thenReturn(true);

        var id = reconciler.reconcile(ORG_ID, new AdminSpec("admin@acme.com", "X", "s3cret"));

        assertThat(id).isEqualTo(existingId);
        verify(userAdminService, never()).createUser(any());
    }

    @Test
    void skipsRotationWhenAdminExistsAndPasswordDoesNotMatch() {
        var existingId = UUID.randomUUID();
        var existing = new UserView(existingId, "admin@acme.com", "X", UserRoleType.ADMIN,
                ORG_ID, true, AuthProviderType.LOCAL, "stored-hash", null, null, false, null);
        when(userQueryService.findByEmail("admin@acme.com")).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches("new-pw", "stored-hash")).thenReturn(false);

        var id = reconciler.reconcile(ORG_ID, new AdminSpec("admin@acme.com", "X", "new-pw"));

        assertThat(id).isEqualTo(existingId);
        verify(userAdminService, never()).createUser(any());
    }

    @Test
    void rejectsAdminInDifferentOrg() {
        var foreignOrg = UUID.randomUUID();
        var existing = new UserView(UUID.randomUUID(), "admin@acme.com", "X", UserRoleType.ADMIN,
                foreignOrg, true, AuthProviderType.LOCAL, "hash", null, null, false, null);
        when(userQueryService.findByEmail("admin@acme.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID,
                new AdminSpec("admin@acme.com", "X", "pw")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different organization");
    }

    @Test
    void lookupIdReturnsExistingUserId() {
        var existingId = UUID.randomUUID();
        var existing = new UserView(existingId, "admin@acme.com", "X", UserRoleType.ADMIN,
                ORG_ID, true, AuthProviderType.LOCAL, "hash", null, null, false, null);
        when(userQueryService.findByEmail("admin@acme.com")).thenReturn(Optional.of(existing));

        assertThat(reconciler.lookupId(ORG_ID, "admin@acme.com")).isEqualTo(existingId);
    }

    @Test
    void lookupIdThrowsWhenUserMissing() {
        when(userQueryService.findByEmail("missing@acme.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reconciler.lookupId(ORG_ID, "missing@acme.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lookupIdThrowsWhenUserInOtherOrg() {
        var existing = new UserView(UUID.randomUUID(), "x@y.z", "X", UserRoleType.ADMIN,
                UUID.randomUUID(), true, AuthProviderType.LOCAL, "hash", null, null, false, null);
        when(userQueryService.findByEmail("x@y.z")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> reconciler.lookupId(ORG_ID, "x@y.z"))
                .isInstanceOf(IllegalStateException.class);
    }
}
