package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessResourceKind;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorRef;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessRequestViewMapperTest {

    @Mock UserQueryService userQueryService;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock ApiConnectorLookupService connectorLookupService;
    @InjectMocks AccessRequestViewMapper mapper;

    @Test
    void toViewEnrichesEmailAndDatasourceName() {
        var requesterId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var e = new AccessGrantRequestEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setRequesterId(requesterId);
        e.setDatasourceId(datasourceId);
        e.setStatus(AccessGrantStatus.PENDING);
        e.setRequestedDuration("PT4H");
        e.setAllowedSchemas(new String[]{"public"});
        when(userQueryService.findById(requesterId)).thenReturn(Optional.of(
                new UserView(requesterId, "u@x.io", "User", UserRoleType.ANALYST, e.getOrganizationId(),
                        true, AuthProviderType.LOCAL, null, null, "en", false, Instant.now())));
        when(datasourceLookupService.findRef(datasourceId))
                .thenReturn(Optional.of(new DatasourceRef(datasourceId, "analytics")));

        var view = mapper.toView(e);

        assertThat(view.requesterEmail()).isEqualTo("u@x.io");
        assertThat(view.resourceKind()).isEqualTo(AccessResourceKind.DATASOURCE);
        assertThat(view.datasourceName()).isEqualTo("analytics");
        assertThat(view.connectorId()).isNull();
        assertThat(view.connectorName()).isNull();
        assertThat(view.allowedSchemas()).containsExactly("public");
    }

    @Test
    void toViewEnrichesConnectorNameForConnectorRequest() {
        var requesterId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        var e = new AccessGrantRequestEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setRequesterId(requesterId);
        e.setConnectorId(connectorId);
        e.setStatus(AccessGrantStatus.PENDING);
        e.setRequestedDuration("PT4H");
        e.setAllowedOperations(new String[]{"listCharges"});
        when(userQueryService.findById(requesterId)).thenReturn(Optional.empty());
        when(connectorLookupService.findRef(connectorId)).thenReturn(Optional.of(
                new ApiConnectorRef(connectorId, "billing-api", ApiProtocol.REST, null)));

        var view = mapper.toView(e);

        assertThat(view.resourceKind()).isEqualTo(AccessResourceKind.API_CONNECTOR);
        assertThat(view.connectorId()).isEqualTo(connectorId);
        assertThat(view.connectorName()).isEqualTo("billing-api");
        assertThat(view.datasourceId()).isNull();
        assertThat(view.datasourceName()).isNull();
        assertThat(view.allowedOperations()).containsExactly("listCharges");
    }

    @Test
    void toViewHandlesMissingLookups() {
        var e = new AccessGrantRequestEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setRequesterId(UUID.randomUUID());
        e.setDatasourceId(UUID.randomUUID());
        e.setStatus(AccessGrantStatus.PENDING);
        e.setRequestedDuration("PT1H");
        when(userQueryService.findById(e.getRequesterId())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(e.getDatasourceId())).thenReturn(Optional.empty());

        var view = mapper.toView(e);

        assertThat(view.requesterEmail()).isNull();
        assertThat(view.datasourceName()).isNull();
        assertThat(view.allowedSchemas()).isNull();
    }
}
