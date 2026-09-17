package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultServiceAccountLookupServiceTest {

    @Mock ServiceAccountRepository repository;
    @InjectMocks DefaultServiceAccountLookupService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void findByUserIdMapsEveryColumn() {
        var entity = account(userId, ServiceAccountSource.UI, new String[] {"list_datasources", "validate_sql"});
        entity.setDescription("Terraform runner");
        entity.setOwnerUserId(ownerId);
        entity.setRateLimitPerMinute(60);
        entity.setRateLimitPerDay(5000);
        when(repository.findById(userId)).thenReturn(Optional.of(entity));

        var view = service.findByUserId(userId).orElseThrow();

        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.organizationId()).isEqualTo(organizationId);
        assertThat(view.description()).isEqualTo("Terraform runner");
        assertThat(view.ownerUserId()).isEqualTo(ownerId);
        assertThat(view.managedBy()).isEqualTo(ServiceAccountSource.UI);
        assertThat(view.mcpToolAllowList()).containsExactly("list_datasources", "validate_sql");
        assertThat(view.rateLimitPerMinute()).isEqualTo(60);
        assertThat(view.rateLimitPerDay()).isEqualTo(5000);
        assertThat(view.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(view.updatedAt()).isEqualTo(entity.getUpdatedAt());
    }

    @Test
    void findByUserIdIsEmptyForAPerson() {
        when(repository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.findByUserId(userId)).isEmpty();
    }

    @Test
    void nullAllowListStaysNullAndEmptyStaysEmpty() {
        var unrestricted = account(userId, ServiceAccountSource.BOOTSTRAP, null);
        var locked = account(UUID.randomUUID(), ServiceAccountSource.UI, new String[0]);
        when(repository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId))
                .thenReturn(List.of(unrestricted, locked));

        var views = service.listByOrganization(organizationId);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).mcpToolAllowList()).isNull();      // every tool
        assertThat(views.get(1).mcpToolAllowList()).isEmpty();     // no tool
        assertThat(views.get(0).managedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
    }

    @Test
    void listByOrganizationIsEmptyWhenTheOrganizationHasNone() {
        when(repository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId)).thenReturn(List.of());

        assertThat(service.listByOrganization(organizationId)).isEmpty();
    }

    private ServiceAccountEntity account(UUID id, ServiceAccountSource source, String[] allowList) {
        var entity = new ServiceAccountEntity();
        entity.setUserId(id);
        entity.setOrganizationId(organizationId);
        entity.setManagedBy(source);
        entity.setMcpToolAllowList(allowList);
        return entity;
    }
}
