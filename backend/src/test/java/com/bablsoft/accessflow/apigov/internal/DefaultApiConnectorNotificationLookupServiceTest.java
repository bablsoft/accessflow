package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiConnectorNotificationLookupServiceTest {

    @Mock private ApiConnectorRepository connectorRepository;
    @InjectMocks private DefaultApiConnectorNotificationLookupService service;

    @Test
    void findMapsConnectorToView() {
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var entity = new ApiConnectorEntity();
        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setName("Stripe");
        when(connectorRepository.findById(id)).thenReturn(Optional.of(entity));

        var view = service.find(id).orElseThrow();

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.name()).isEqualTo("Stripe");
    }

    @Test
    void findEmptyWhenMissing() {
        var id = UUID.randomUUID();
        when(connectorRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.find(id)).isEmpty();
    }
}
