package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.IllegalUserOperationException;
import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.QuotaType;
import com.bablsoft.accessflow.core.api.UpdateUserCommand;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
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
    @Mock QuotaService quotaService;
    UserAdminServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID orgId = UUID.randomUUID();
    private final UUID otherOrgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserAdminServiceImpl(userRepository, organizationRepository, quotaService,
                objectMapper);
    }

    @Test
    void listUsersReturnsPageMappedToView() {
        var entity = buildUser(userId, orgId, "alice@example.com", UserRoleType.ANALYST);
        when(userRepository.findAllByOrganization_Id(orgId,
                org.springframework.data.domain.PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        var page = service.listUsers(orgId,
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).email()).isEqualTo("alice@example.com");
        assertThat(page.content().get(0).organizationId()).isEqualTo(orgId);
    }

    @Test
    void createUserPersistsHashedPasswordAndLocalProvider() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateUserCommand(orgId, "new@example.com", "New User",
                "hashed-password", UserRoleType.REVIEWER, false);
        var result = service.createUser(command);

        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(result.authProvider()).isEqualTo(AuthProviderType.LOCAL);
        assertThat(result.active()).isTrue();
        assertThat(result.passwordHash()).isEqualTo("hashed-password");
        assertThat(result.platformAdmin()).isFalse();
        verify(quotaService).checkUserQuota(orgId);
    }

    @Test
    void createUserPersistsPlatformAdminFlag() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(userRepository.existsByEmail("plat@example.com")).thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createUser(new CreateUserCommand(orgId, "plat@example.com", "Plat",
                "hash", UserRoleType.ADMIN, true));

        assertThat(result.platformAdmin()).isTrue();
    }

    @Test
    void createUserThrowsWhenQuotaExceeded() {
        when(userRepository.existsByEmail("over@example.com")).thenReturn(false);
        org.mockito.Mockito.doThrow(new QuotaExceededException(QuotaType.USER, orgId, 5, 5))
                .when(quotaService).checkUserQuota(orgId);

        assertThatThrownBy(() -> service.createUser(new CreateUserCommand(orgId, "over@example.com",
                "Over", "hash", UserRoleType.ANALYST, false)))
                .isInstanceOf(QuotaExceededException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void setPlatformAdminUpdatesFlag() {
        var entity = buildUser(userId, orgId, "user@example.com", UserRoleType.ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        var result = service.setPlatformAdmin(userId, true);

        assertThat(result.platformAdmin()).isTrue();
        assertThat(entity.isPlatformAdmin()).isTrue();
    }

    @Test
    void setPlatformAdminThrowsWhenUserMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPlatformAdmin(userId, true))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void createUserThrowsWhenEmailAlreadyExists() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(new CreateUserCommand(orgId,
                "dup@example.com", "Dup", "hash", UserRoleType.ANALYST, false)))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserAppliesNonNullFields() {
        var entity = buildUser(userId, orgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        var result = service.updateUser(userId, orgId, adminId,
                new UpdateUserCommand(UserRoleType.REVIEWER, false, null, null));

        assertThat(result.role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(result.active()).isFalse();
        assertThat(result.displayName()).isEqualTo("Alice");
    }

    @Test
    void updateUserOnDifferentOrgThrowsNotFound() {
        var entity = buildUser(userId, otherOrgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateUser(userId, orgId, adminId,
                new UpdateUserCommand(UserRoleType.REVIEWER, null, null, null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateUserBlocksSelfDemotionFromAdmin() {
        var entity = buildUser(adminId, orgId, "admin@example.com", UserRoleType.ADMIN);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateUser(adminId, orgId, adminId,
                new UpdateUserCommand(UserRoleType.ANALYST, null, null, null)))
                .isInstanceOf(IllegalUserOperationException.class);
    }

    @Test
    void updateUserBlocksSelfDeactivation() {
        var entity = buildUser(adminId, orgId, "admin@example.com", UserRoleType.ADMIN);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateUser(adminId, orgId, adminId,
                new UpdateUserCommand(null, false, null, null)))
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

    @Test
    void findByIdsReturnsEmptyMapWhenIdsAreNullOrEmpty() {
        assertThat(service.findByIds(orgId, null)).isEmpty();
        assertThat(service.findByIds(orgId, List.of())).isEmpty();
        verify(userRepository, never()).findAllByOrganization_IdAndIdIn(any(), any());
    }

    @Test
    void findByIdsReturnsViewsKeyedById() {
        var entity = buildUser(userId, orgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findAllByOrganization_IdAndIdIn(orgId, List.of(userId)))
                .thenReturn(List.of(entity));

        var result = service.findByIds(orgId, List.of(userId));

        assertThat(result).hasSize(1);
        assertThat(result.get(userId).email()).isEqualTo("user@example.com");
    }

    @Test
    void updateUserPersistsAttributesAsJson() {
        var entity = buildUser(userId, orgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        service.updateUser(userId, orgId, adminId,
                new UpdateUserCommand(null, null, null, Map.of("region", "EU")));

        assertThat(entity.getAttributes()).contains("\"region\"").contains("EU");
    }

    @Test
    void getUserAttributesParsesStoredJson() {
        var entity = buildUser(userId, orgId, "user@example.com", UserRoleType.ANALYST);
        entity.setAttributes("{\"region\":\"EU\",\"tier\":\"gold\"}");
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThat(service.getUserAttributes(userId, orgId))
                .containsEntry("region", "EU")
                .containsEntry("tier", "gold");
    }

    @Test
    void getUserAttributesThrowsForUserInDifferentOrg() {
        var entity = buildUser(userId, otherOrgId, "user@example.com", UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getUserAttributes(userId, orgId))
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
