package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
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
class DefaultApiConnectorLookupServiceTest {

    @Mock ApiConnectorRepository connectorRepository;
    @InjectMocks DefaultApiConnectorLookupService service;

    private final UUID connectorId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID reviewPlanId = UUID.randomUUID();

    private ApiConnectorEntity connector(UUID id, String name, UUID planId) {
        var e = new ApiConnectorEntity();
        e.setId(id);
        e.setOrganizationId(organizationId);
        e.setName(name);
        e.setProtocol(ApiProtocol.REST);
        e.setBaseUrl("https://api.test");
        e.setReviewPlanId(planId);
        e.setActive(true);
        return e;
    }

    @Test
    void findRefMapsEntityIncludingReviewPlanId() {
        when(connectorRepository.findById(connectorId))
                .thenReturn(Optional.of(connector(connectorId, "billing-api", reviewPlanId)));

        var ref = service.findRef(connectorId).orElseThrow();

        assertThat(ref.id()).isEqualTo(connectorId);
        assertThat(ref.name()).isEqualTo("billing-api");
        assertThat(ref.protocol()).isEqualTo(ApiProtocol.REST);
        assertThat(ref.reviewPlanId()).isEqualTo(reviewPlanId);
    }

    @Test
    void findRefMapsNullReviewPlanId() {
        when(connectorRepository.findById(connectorId))
                .thenReturn(Optional.of(connector(connectorId, "billing-api", null)));

        assertThat(service.findRef(connectorId).orElseThrow().reviewPlanId()).isNull();
    }

    @Test
    void findRefEmptyWhenConnectorUnknown() {
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.empty());

        assertThat(service.findRef(connectorId)).isEmpty();
    }

    @Test
    void findActiveRefsByOrganizationDelegatesToActiveOnlyNameOrderedQuery() {
        var otherId = UUID.randomUUID();
        when(connectorRepository.findByOrganizationIdAndActiveTrueOrderByNameAsc(organizationId))
                .thenReturn(List.of(connector(connectorId, "alpha-api", null),
                        connector(otherId, "billing-api", reviewPlanId)));

        var refs = service.findActiveRefsByOrganization(organizationId);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).id()).isEqualTo(connectorId);
        assertThat(refs.get(0).name()).isEqualTo("alpha-api");
        assertThat(refs.get(1).id()).isEqualTo(otherId);
        assertThat(refs.get(1).reviewPlanId()).isEqualTo(reviewPlanId);
    }
}
