package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.CreateUserCommand;
import com.partqam.accessflow.core.api.SetupAlreadyCompletedException;
import com.partqam.accessflow.core.api.SetupCommand;
import com.partqam.accessflow.core.api.UserAdminService;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock UserAdminService userAdminService;
    @InjectMocks BootstrapServiceImpl service;

    @Test
    void isSetupRequiredTrueWhenNoActiveAdmin() {
        when(userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true)).thenReturn(false);

        assertThat(service.isSetupRequired()).isTrue();
    }

    @Test
    void isSetupRequiredFalseWhenActiveAdminExists() {
        when(userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true)).thenReturn(true);

        assertThat(service.isSetupRequired()).isFalse();
    }

    @Test
    void performSetupCreatesOrgAndAdminWhenNoAdminExists() {
        when(userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true)).thenReturn(false);
        when(organizationRepository.existsBySlug("acme")).thenReturn(false);
        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var createdUserId = UUID.randomUUID();
        when(userAdminService.createUser(any(CreateUserCommand.class)))
                .thenAnswer(inv -> {
                    CreateUserCommand cmd = inv.getArgument(0);
                    return new UserView(createdUserId, cmd.email(), cmd.displayName(),
                            cmd.role(), cmd.organizationId(), true,
                            AuthProviderType.LOCAL, cmd.passwordHash(), null, null, null);
                });

        var result = service.performSetup(new SetupCommand(
                "Acme", "admin@acme.com", "Acme Admin", "hashed-pass"));

        assertThat(result.userId()).isEqualTo(createdUserId);
        assertThat(result.organizationId()).isNotNull();

        var orgCaptor = ArgumentCaptor.forClass(OrganizationEntity.class);
        verify(organizationRepository).save(orgCaptor.capture());
        assertThat(orgCaptor.getValue().getName()).isEqualTo("Acme");
        assertThat(orgCaptor.getValue().getSlug()).isEqualTo("acme");

        var cmdCaptor = ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userAdminService).createUser(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().email()).isEqualTo("admin@acme.com");
        assertThat(cmdCaptor.getValue().role()).isEqualTo(UserRoleType.ADMIN);
        assertThat(cmdCaptor.getValue().passwordHash()).isEqualTo("hashed-pass");
    }

    @Test
    void performSetupAppendsSuffixOnSlugCollision() {
        when(userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true)).thenReturn(false);
        when(organizationRepository.existsBySlug("acme")).thenReturn(true);
        when(organizationRepository.existsBySlug(org.mockito.ArgumentMatchers.startsWith("acme-")))
                .thenReturn(false);
        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userAdminService.createUser(any(CreateUserCommand.class)))
                .thenAnswer(inv -> {
                    CreateUserCommand cmd = inv.getArgument(0);
                    return new UserView(UUID.randomUUID(), cmd.email(), cmd.displayName(),
                            cmd.role(), cmd.organizationId(), true,
                            AuthProviderType.LOCAL, cmd.passwordHash(), null, null, null);
                });

        service.performSetup(new SetupCommand("Acme", "x@y.z", "X", "h"));

        var orgCaptor = ArgumentCaptor.forClass(OrganizationEntity.class);
        verify(organizationRepository).save(orgCaptor.capture());
        assertThat(orgCaptor.getValue().getSlug()).startsWith("acme-").hasSize("acme".length() + 1 + 6);
    }

    @Test
    void performSetupSlugifiesPunctuationAndCase() {
        when(userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true)).thenReturn(false);
        when(organizationRepository.existsBySlug("widgets-co")).thenReturn(false);
        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userAdminService.createUser(any(CreateUserCommand.class)))
                .thenAnswer(inv -> {
                    CreateUserCommand cmd = inv.getArgument(0);
                    return new UserView(UUID.randomUUID(), cmd.email(), cmd.displayName(),
                            cmd.role(), cmd.organizationId(), true,
                            AuthProviderType.LOCAL, cmd.passwordHash(), null, null, null);
                });

        service.performSetup(new SetupCommand("Widgets & Co!", "x@y.z", "X", "h"));

        var orgCaptor = ArgumentCaptor.forClass(OrganizationEntity.class);
        verify(organizationRepository).save(orgCaptor.capture());
        assertThat(orgCaptor.getValue().getSlug()).isEqualTo("widgets-co");
    }

    @Test
    void performSetupThrowsWhenAdminAlreadyExists() {
        when(userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true)).thenReturn(true);

        assertThatThrownBy(() -> service.performSetup(new SetupCommand(
                "Acme", "admin@acme.com", "Acme Admin", "hashed")))
                .isInstanceOf(SetupAlreadyCompletedException.class);

        verify(organizationRepository, never()).save(any());
        verify(userAdminService, never()).createUser(any());
    }
}
