package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceImplTest {

    @Mock UserRepository userRepository;
    @InjectMocks UserQueryServiceImpl service;

    @Test
    void findByEmailReturnsEmptyWhenUserNotFound() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(service.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void findByEmailMapsEntityFieldsCorrectly() {
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entity = buildUser(userId, orgId, "alice@example.com", UserRoleType.ANALYST);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(entity));

        var result = service.findByEmail("alice@example.com");

        assertThat(result).isPresent();
        var view = result.get();
        assertThat(view.id()).isEqualTo(userId);
        assertThat(view.email()).isEqualTo("alice@example.com");
        assertThat(view.role()).isEqualTo(UserRoleType.ANALYST);
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.active()).isTrue();
        assertThat(view.authProvider()).isEqualTo(AuthProviderType.LOCAL);
    }

    @Test
    void findByIdDelegatesToRepository() {
        var id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findById(id)).isEmpty();
    }

    @Test
    void countActiveServiceAccountsByRoleNameGroupsByEffectiveRoleName() {
        var orgId = UUID.randomUUID();
        var customRole = new RoleEntity();
        customRole.setName("Triage bots");
        var legacyReviewer = buildUser(UUID.randomUUID(), orgId, "a@example.com", UserRoleType.REVIEWER);
        var linkedReviewer = buildUser(UUID.randomUUID(), orgId, "b@example.com", UserRoleType.REVIEWER);
        var custom = buildUser(UUID.randomUUID(), orgId, "c@example.com", UserRoleType.ANALYST);
        custom.setRoleRef(customRole);
        var roleless = buildUser(UUID.randomUUID(), orgId, "d@example.com", null);
        when(userRepository.findAllByOrganization_IdAndPrincipalTypeAndActiveTrue(orgId,
                PrincipalType.SERVICE_ACCOUNT))
                .thenReturn(List.of(legacyReviewer, linkedReviewer, custom, roleless));

        var counts = service.countActiveServiceAccountsByRoleName(orgId);

        assertThat(counts).containsExactly(
                entry("REVIEWER", 2L),
                entry("Triage bots", 1L));
    }

    @Test
    void countActiveServiceAccountsByRoleNameIsEmptyWithoutServiceAccounts() {
        var orgId = UUID.randomUUID();
        when(userRepository.findAllByOrganization_IdAndPrincipalTypeAndActiveTrue(orgId,
                PrincipalType.SERVICE_ACCOUNT)).thenReturn(List.of());

        assertThat(service.countActiveServiceAccountsByRoleName(orgId)).isEmpty();
    }

    private UserEntity buildUser(UUID userId, UUID orgId, String email, UserRoleType role) {
        var org = new OrganizationEntity();
        org.setId(orgId);

        var user = new UserEntity();
        user.setId(userId);
        user.setEmail(email);
        user.setDisplayName("Alice");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        user.setPasswordHash("hashed");
        return user;
    }
}
