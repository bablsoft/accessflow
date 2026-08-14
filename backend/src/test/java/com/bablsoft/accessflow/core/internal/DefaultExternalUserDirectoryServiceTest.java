package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateExternalUserCommand;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ExternalIdAlreadyExistsException;
import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.QuotaType;
import com.bablsoft.accessflow.core.api.SessionRevocationService;
import com.bablsoft.accessflow.core.api.UpdateExternalUserCommand;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.UserDeactivatedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultExternalUserDirectoryServiceTest {

    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock RoleRepository roleRepository;
    @Mock QuotaService quotaService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SessionRevocationService sessionRevocationService;

    DefaultExternalUserDirectoryService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultExternalUserDirectoryService(userRepository, organizationRepository,
                roleRepository, quotaService, eventPublisher, sessionRevocationService);
        lenient().when(roleRepository.findByNameAndSystemTrue(any())).thenReturn(Optional.empty());
    }

    @Test
    void createExternalPersistsScimProviderWithoutPassword() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createExternal(new CreateExternalUserCommand(
                orgId, "Jane@Example.com ", "Jane", "ext-1", UserRoleType.ANALYST));

        assertThat(view.email()).isEqualTo("jane@example.com");
        assertThat(view.authProvider()).isEqualTo(AuthProviderType.SCIM);
        assertThat(view.passwordHash()).isNull();
        assertThat(view.scimExternalId()).isEqualTo("ext-1");
        assertThat(view.active()).isTrue();
        assertThat(view.role()).isEqualTo(UserRoleType.ANALYST);
        verify(quotaService).checkUserQuota(orgId);
    }

    @Test
    void createExternalRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createExternal(new CreateExternalUserCommand(
                orgId, "dup@example.com", "Dup", null, UserRoleType.ANALYST)))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void createExternalRejectsDuplicateExternalId() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.findByOrganization_IdAndScimExternalId(orgId, "ext-1"))
                .thenReturn(Optional.of(user(UUID.randomUUID(), true)));

        assertThatThrownBy(() -> service.createExternal(new CreateExternalUserCommand(
                orgId, "new@example.com", "New", "ext-1", UserRoleType.ANALYST)))
                .isInstanceOf(ExternalIdAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void createExternalEnforcesQuota() {
        when(userRepository.existsByEmail("over@example.com")).thenReturn(false);
        doThrow(new QuotaExceededException(QuotaType.USER, orgId, 5, 5))
                .when(quotaService).checkUserQuota(orgId);

        assertThatThrownBy(() -> service.createExternal(new CreateExternalUserCommand(
                orgId, "over@example.com", "Over", null, UserRoleType.ANALYST)))
                .isInstanceOf(QuotaExceededException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateExternalDeactivationPublishesEventOnce() {
        var entity = user(userId, true);
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.of(entity));

        service.updateExternal(orgId, userId,
                new UpdateExternalUserCommand(null, null, null, false));

        assertThat(entity.isActive()).isFalse();
        verify(eventPublisher).publishEvent(new UserDeactivatedEvent(userId, orgId));
        verify(sessionRevocationService).revokeAllSessions(userId);
    }

    @Test
    void updateExternalDeactivatingInactiveUserIsIdempotent() {
        var entity = user(userId, false);
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.of(entity));

        service.updateExternal(orgId, userId,
                new UpdateExternalUserCommand(null, null, null, false));

        verify(eventPublisher, never()).publishEvent(any());
        verify(sessionRevocationService, never()).revokeAllSessions(any());
    }

    @Test
    void updateExternalReactivationChecksQuota() {
        var entity = user(userId, false);
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.of(entity));
        doThrow(new QuotaExceededException(QuotaType.USER, orgId, 5, 5))
                .when(quotaService).checkUserQuota(orgId);

        assertThatThrownBy(() -> service.updateExternal(orgId, userId,
                new UpdateExternalUserCommand(null, null, null, true)))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(entity.isActive()).isFalse();
    }

    @Test
    void updateExternalRejectsEmailCollision() {
        var entity = user(userId, true);
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.of(entity));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.updateExternal(orgId, userId,
                new UpdateExternalUserCommand("taken@example.com", null, null, null)))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void updateExternalRejectsExternalIdCollision() {
        var entity = user(userId, true);
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.of(entity));
        when(userRepository.findByOrganization_IdAndScimExternalId(orgId, "ext-9"))
                .thenReturn(Optional.of(user(UUID.randomUUID(), true)));

        assertThatThrownBy(() -> service.updateExternal(orgId, userId,
                new UpdateExternalUserCommand(null, null, "ext-9", null)))
                .isInstanceOf(ExternalIdAlreadyExistsException.class);
    }

    @Test
    void updateExternalNeverTouchesUnownedFields() {
        var entity = user(userId, true);
        entity.setPasswordHash("hash");
        entity.setPlatformAdmin(true);
        entity.setTotpEnabled(true);
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.of(entity));

        service.updateExternal(orgId, userId,
                new UpdateExternalUserCommand("new@example.com", "New Name", "ext-2", null));

        assertThat(entity.getPasswordHash()).isEqualTo("hash");
        assertThat(entity.isPlatformAdmin()).isTrue();
        assertThat(entity.isTotpEnabled()).isTrue();
        assertThat(entity.getAuthProvider()).isEqualTo(AuthProviderType.LOCAL);
    }

    @Test
    void updateExternalUnknownUserThrowsNotFound() {
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateExternal(orgId, userId,
                new UpdateExternalUserCommand(null, null, null, null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void findersMapToViews() {
        var entity = user(userId, true);
        when(userRepository.findByOrganization_IdAndId(orgId, userId))
                .thenReturn(Optional.of(entity));
        when(userRepository.findByOrganization_IdAndEmail(orgId, "jane@example.com"))
                .thenReturn(Optional.of(entity));
        when(userRepository.findByOrganization_IdAndScimExternalId(orgId, "ext-1"))
                .thenReturn(Optional.of(entity));

        assertThat(service.findById(orgId, userId)).isPresent();
        assertThat(service.findByEmail(orgId, "Jane@Example.com")).isPresent();
        assertThat(service.findByExternalId(orgId, "ext-1")).isPresent();
    }

    @Test
    void listReturnsOffsetPage() {
        var entity = user(userId, true);
        when(userRepository.findAllByOrganization_Id(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity), Pageable.ofSize(2), 7));

        var page = service.list(orgId, 4, 2);

        assertThat(page.content()).hasSize(1);
        assertThat(page.totalResults()).isEqualTo(7);
    }

    private UserEntity user(UUID id, boolean active) {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var entity = new UserEntity();
        entity.setId(id);
        entity.setOrganization(org);
        entity.setEmail(id + "@example.com");
        entity.setActive(active);
        return entity;
    }
}
