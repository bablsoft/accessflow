package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ExternalLocalAccountConflictException;
import com.bablsoft.accessflow.core.api.InactiveUserException;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserProvisioningServiceTest {

    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;

    private DefaultUserProvisioningService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultUserProvisioningService(userRepository, organizationRepository);
    }

    @Test
    void returnsExistingExternalUserWithoutCreating() {
        var existing = newUser("alice@example.com", AuthProviderType.OAUTH2);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(existing));

        var view = service.findOrProvision(orgId, "Alice@example.com", "Alice",
                AuthProviderType.OAUTH2, UserRoleType.ANALYST);

        assertThat(view.id()).isEqualTo(existing.getId());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void rejectsExistingLocalUserWithPassword() {
        var existing = newUser("admin@example.com", AuthProviderType.LOCAL);
        existing.setPasswordHash("$2a$10$abc");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.findOrProvision(orgId, "admin@example.com", "Admin",
                AuthProviderType.OAUTH2, UserRoleType.ANALYST))
                .isInstanceOf(ExternalLocalAccountConflictException.class);
    }

    @Test
    void rejectsInactiveExistingUser() {
        var existing = newUser("disabled@example.com", AuthProviderType.OAUTH2);
        existing.setActive(false);
        when(userRepository.findByEmail("disabled@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.findOrProvision(orgId, "disabled@example.com", "X",
                AuthProviderType.OAUTH2, UserRoleType.ANALYST))
                .isInstanceOf(InactiveUserException.class);
    }

    @Test
    void provisionsNewUserWithGivenDefaults() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        var organization = new OrganizationEntity();
        organization.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(organization);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.findOrProvision(orgId, "NEW@example.com", "New User",
                AuthProviderType.OAUTH2, UserRoleType.REVIEWER);

        assertThat(view.email()).isEqualTo("new@example.com");
        assertThat(view.role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(view.authProvider()).isEqualTo(AuthProviderType.OAUTH2);

        var captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isNull();
        assertThat(captor.getValue().isActive()).isTrue();
    }

    private UserEntity newUser(String email, AuthProviderType authProvider) {
        var organization = new OrganizationEntity();
        organization.setId(orgId);
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setEmail(email);
        entity.setDisplayName("Test");
        entity.setRole(UserRoleType.ANALYST);
        entity.setAuthProvider(authProvider);
        entity.setActive(true);
        return entity;
    }
}
