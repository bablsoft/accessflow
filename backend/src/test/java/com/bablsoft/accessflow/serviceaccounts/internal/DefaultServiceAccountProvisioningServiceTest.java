package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultServiceAccountProvisioningServiceTest {

    @Mock ServiceAccountRepository repository;
    @Mock UserAdminService userAdminService;
    @InjectMocks DefaultServiceAccountProvisioningService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void firstRegistrationFlipsTheDiscriminatorAndCreatesTheDetailRow() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.ensureRegistered(organizationId, userId, ServiceAccountSource.BOOTSTRAP);

        verify(userAdminService).setPrincipalType(userId, organizationId, PrincipalType.SERVICE_ACCOUNT);
        var saved = ArgumentCaptor.forClass(ServiceAccountEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getOrganizationId()).isEqualTo(organizationId);
        assertThat(saved.getValue().getManagedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
        assertThat(saved.getValue().getMcpToolAllowList()).isNull();
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.managedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
    }

    @Test
    void reRegistrationOnlyReassertsManagedByAndKeepsEveryUiOwnedField() {
        var ownerId = UUID.randomUUID();
        var existing = new ServiceAccountEntity();
        existing.setUserId(userId);
        existing.setOrganizationId(organizationId);
        existing.setManagedBy(ServiceAccountSource.UI);
        existing.setDescription("edited in the UI");
        existing.setOwnerUserId(ownerId);
        existing.setMcpToolAllowList(new String[] {"validate_sql"});
        existing.setRateLimitPerMinute(10);
        existing.setRateLimitPerDay(100);
        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        var view = service.ensureRegistered(organizationId, userId, ServiceAccountSource.BOOTSTRAP);

        verify(userAdminService).setPrincipalType(userId, organizationId, PrincipalType.SERVICE_ACCOUNT);
        assertThat(existing.getManagedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
        assertThat(view.description()).isEqualTo("edited in the UI");
        assertThat(view.ownerUserId()).isEqualTo(ownerId);
        assertThat(view.mcpToolAllowList()).containsExactly("validate_sql");
        assertThat(view.rateLimitPerMinute()).isEqualTo(10);
        assertThat(view.rateLimitPerDay()).isEqualTo(100);
    }

    @Test
    void unknownOrForeignUserPropagatesAndWritesNothing() {
        when(userAdminService.setPrincipalType(userId, organizationId, PrincipalType.SERVICE_ACCOUNT))
                .thenThrow(new UserNotFoundException(userId));

        assertThatThrownBy(() -> service.ensureRegistered(organizationId, userId, ServiceAccountSource.UI))
                .isInstanceOf(UserNotFoundException.class);

        verify(repository, never()).save(any());
    }
}
