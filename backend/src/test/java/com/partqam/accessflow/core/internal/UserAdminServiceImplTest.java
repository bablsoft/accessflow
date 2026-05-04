package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CreateUserCommand;
import com.partqam.accessflow.core.api.EmailAlreadyExistsException;
import com.partqam.accessflow.core.api.IllegalUserOperationException;
import com.partqam.accessflow.core.api.UpdateUserCommand;
import com.partqam.accessflow.core.api.UserNotFoundException;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;
    @InjectMocks UserAdminServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID otherOrgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @Test
    void listUsersReturnsPageMappedToView() {
        var entity = buildUser(userId, orgId, "alice@example.com", UserRoleType.ANALYST);
        when(userRepository.findAllByOrganization_Id(orgId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        var page = service.listUsers(orgId, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("alice@example.com");
        assertThat(page.getContent().get(0).organizationId()).isEqualTo(orgId);
    }

    @Test
    void createUserPersistsHashedPasswordAndLocalProvider() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateUserCommand(orgId, "new@example.com", "New User",
                "hashed-password", UserRoleType.REVIEWER);
        var result = service.createUser(command);

        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(result.authProvider()).isEqualTo(AuthProviderType.LOCAL);
        assertThat(result.active()).isTrue();
        assertThat(result.passwordHash()).isEqualTo("hashed-password");
    }

    @Test
    void createUserThrowsWhenEmailAlreadyExists() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(new CreateUserCommand(orgId,
                "dup@example.com", "Dup", "hash", UserRoleType.ANALYST)))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserAppliesNonNullFields() {
        var entity = buildUser(userId, orgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        var result = service.updateUser(userId, orgId, adminId,
                new UpdateUserCommand(UserRoleType.REVIEWER, false, null));

        assertThat(result.role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(result.active()).isFalse();
        assertThat(result.displayName()).isEqualTo("Alice");
    }

    @Test
    void updateUserOnDifferentOrgThrowsNotFound() {
        var entity = buildUser(userId, otherOrgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateUser(userId, orgId, adminId,
                new UpdateUserCommand(UserRoleType.REVIEWER, null, null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateUserBlocksSelfDemotionFromAdmin() {
        var entity = buildUser(adminId, orgId, "admin@example.com", UserRoleType.ADMIN);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateUser(adminId, orgId, adminId,
                new UpdateUserCommand(UserRoleType.ANALYST, null, null)))
                .isInstanceOf(IllegalUserOperationException.class);
    }

    @Test
    void updateUserBlocksSelfDeactivation() {
        var entity = buildUser(adminId, orgId, "admin@example.com", UserRoleType.ADMIN);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateUser(adminId, orgId, adminId,
                new UpdateUserCommand(null, false, null)))
                .isInstanceOf(IllegalUserOperationException.class);
    }

    @Test
    void deactivateUserSetsActiveFalse() {
        var entity = buildUser(userId, orgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        var result = service.deactivateUser(userId, orgId, adminId);

        assertThat(result.active()).isFalse();
    }

    @Test
    void deactivateUserBlocksSelfDeactivation() {
        assertThatThrownBy(() -> service.deactivateUser(adminId, orgId, adminId))
                .isInstanceOf(IllegalUserOperationException.class);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void deactivateUserOnDifferentOrgThrowsNotFound() {
        var entity = buildUser(userId, otherOrgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.deactivateUser(userId, orgId, adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deactivateUserNotFoundThrows() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateUser(userId, orgId, adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    private UserEntity buildUser(UUID id, UUID organizationId, String email, UserRoleType role) {
        var org = new OrganizationEntity();
        org.setId(organizationId);

        var entity = new UserEntity();
        entity.setId(id);
        entity.setEmail(email);
        entity.setDisplayName("Alice");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        entity.setPasswordHash("hashed");
        return entity;
    }
}
