package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
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
class ApiConnectorDelegationScopeResolverTest {

    @Mock
    private ApiConnectorRepository connectorRepository;

    @InjectMocks
    private ApiConnectorDelegationScopeResolver resolver;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    private ApiConnectorEntity connector(UUID owningOrgId) {
        var connector = new ApiConnectorEntity();
        connector.setId(connectorId);
        connector.setOrganizationId(owningOrgId);
        connector.setName("Billing API");
        return connector;
    }

    @Test
    void answersForTheApiConnectorScopeKind() {
        assertThat(resolver.supportedKind()).isEqualTo(DelegationScopeKind.API_CONNECTOR);
    }

    @Test
    void resolvesTheNameOfAConnectorInTheSameOrganization() {
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(orgId)));

        assertThat(resolver.resolveName(orgId, connectorId)).contains("Billing API");
    }

    @Test
    void refusesToResolveAcrossAnOrganizationBoundary() {
        when(connectorRepository.findById(connectorId))
                .thenReturn(Optional.of(connector(UUID.randomUUID())));

        assertThat(resolver.resolveName(orgId, connectorId)).isEmpty();
    }

    @Test
    void returnsEmptyForAnUnknownConnector() {
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.empty());

        assertThat(resolver.resolveName(orgId, connectorId)).isEmpty();
    }
}
