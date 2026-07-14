package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestService.ConnectorOperationOption;
import com.bablsoft.accessflow.access.api.AccessRequestService.ConnectorOption;
import com.bablsoft.accessflow.access.api.AccessRequestService.DatasourceOption;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.api.AccessResourceKind;
import com.bablsoft.accessflow.access.api.AccessReviewService.DecisionOutcome;
import com.bablsoft.accessflow.access.api.AccessReviewService.PendingAccessRequest;
import com.bablsoft.accessflow.access.api.AccessReviewService.RevocationOutcome;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessWebResponseMappersTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void accessRequestResponseFromCopiesFields() {
        var view = new AccessRequestView(id, UUID.randomUUID(), UUID.randomUUID(), "u@x.io",
                AccessResourceKind.DATASOURCE, UUID.randomUUID(), "db", null, null,
                true, false, true, List.of("public"), null, null, "PT4H", "j",
                true, AccessGrantStatus.PENDING, null, null, Instant.now(), Instant.now());
        var response = AccessRequestResponse.from(view);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.resourceKind()).isEqualTo(AccessResourceKind.DATASOURCE);
        assertThat(response.datasourceName()).isEqualTo("db");
        assertThat(response.connectorId()).isNull();
        assertThat(response.canDdl()).isTrue();
        assertThat(response.status()).isEqualTo(AccessGrantStatus.PENDING);
    }

    @Test
    void accessRequestResponseFromCopiesConnectorFields() {
        var connectorId = UUID.randomUUID();
        var view = new AccessRequestView(id, UUID.randomUUID(), UUID.randomUUID(), "u@x.io",
                AccessResourceKind.API_CONNECTOR, null, null, connectorId, "billing-api",
                true, true, false, null, null, List.of("getPets"), "PT4H", "j",
                false, AccessGrantStatus.PENDING, null, null, Instant.now(), Instant.now());
        var response = AccessRequestResponse.from(view);
        assertThat(response.resourceKind()).isEqualTo(AccessResourceKind.API_CONNECTOR);
        assertThat(response.connectorId()).isEqualTo(connectorId);
        assertThat(response.connectorName()).isEqualTo("billing-api");
        assertThat(response.allowedOperations()).containsExactly("getPets");
        assertThat(response.datasourceId()).isNull();
    }

    @Test
    void accessRequestPageResponseFromMapsContent() {
        var view = new AccessRequestView(id, UUID.randomUUID(), UUID.randomUUID(), "u@x.io",
                AccessResourceKind.DATASOURCE, UUID.randomUUID(), "db", null, null,
                true, false, false, null, null, null, "PT4H", "j",
                false, AccessGrantStatus.APPROVED, null, null, Instant.now(), Instant.now());
        var page = new PageResponse<>(List.of(view), 0, 20, 1, 1);
        var response = AccessRequestPageResponse.from(page);
        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void pendingAccessRequestItemFromMapsSummaries() {
        var datasourceId = UUID.randomUUID();
        var requesterId = UUID.randomUUID();
        var pending = new PendingAccessRequest(id, AccessResourceKind.DATASOURCE,
                datasourceId, "db", null, null, requesterId, "u@x.io",
                true, false, false, List.of("public"), null, null, "PT4H", "j", true, 1,
                Instant.now());
        var item = PendingAccessRequestItem.from(pending);
        assertThat(item.resourceKind()).isEqualTo(AccessResourceKind.DATASOURCE);
        assertThat(item.datasource().id()).isEqualTo(datasourceId);
        assertThat(item.connector()).isNull();
        assertThat(item.requestedBy().email()).isEqualTo("u@x.io");
        assertThat(item.currentStage()).isEqualTo(1);
    }

    @Test
    void pendingAccessRequestItemFromMapsConnectorSummary() {
        var connectorId = UUID.randomUUID();
        var pending = new PendingAccessRequest(id, AccessResourceKind.API_CONNECTOR,
                null, null, connectorId, "billing-api", UUID.randomUUID(), "u@x.io",
                true, true, false, null, null, List.of("getPets"), "PT4H", "j", false, 0,
                Instant.now());
        var item = PendingAccessRequestItem.from(pending);
        assertThat(item.resourceKind()).isEqualTo(AccessResourceKind.API_CONNECTOR);
        assertThat(item.datasource()).isNull();
        assertThat(item.connector().id()).isEqualTo(connectorId);
        assertThat(item.connector().name()).isEqualTo("billing-api");
        assertThat(item.allowedOperations()).containsExactly("getPets");
    }

    @Test
    void pendingPageResponseFromMapsContent() {
        var pending = new PendingAccessRequest(id, AccessResourceKind.DATASOURCE,
                UUID.randomUUID(), "db", null, null, UUID.randomUUID(),
                "u@x.io", true, false, false, null, null, null, "PT4H", "j", false, 0,
                Instant.now());
        var page = new PageResponse<>(List.of(pending), 0, 20, 1, 1);
        var response = PendingAccessRequestsPageResponse.from(page);
        assertThat(response.content()).hasSize(1);
    }

    @Test
    void decisionResponseFromCopiesOutcome() {
        var outcome = new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                AccessGrantStatus.APPROVED, false);
        var response = AccessDecisionResponse.from(id, outcome);
        assertThat(response.accessRequestId()).isEqualTo(id);
        assertThat(response.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(response.resultingStatus()).isEqualTo(AccessGrantStatus.APPROVED);
    }

    @Test
    void revocationResponseFromCopiesOutcome() {
        var response = AccessRevocationResponse.from(id,
                new RevocationOutcome(AccessGrantStatus.REVOKED, false));
        assertThat(response.resultingStatus()).isEqualTo(AccessGrantStatus.REVOKED);
        assertThat(response.noOp()).isFalse();
    }

    @Test
    void requestableDatasourceResponseFromCopiesOption() {
        var dsId = UUID.randomUUID();
        var response = RequestableDatasourceResponse.from(new DatasourceOption(dsId, "analytics"));
        assertThat(response.id()).isEqualTo(dsId);
        assertThat(response.name()).isEqualTo("analytics");
    }

    @Test
    void requestableConnectorResponseFromCopiesOption() {
        var connectorId = UUID.randomUUID();
        var response = RequestableConnectorResponse.from(
                new ConnectorOption(connectorId, "billing-api", "REST"));
        assertThat(response.id()).isEqualTo(connectorId);
        assertThat(response.name()).isEqualTo("billing-api");
        assertThat(response.protocol()).isEqualTo("REST");
    }

    @Test
    void requestableConnectorOperationResponseFromCopiesOption() {
        var response = RequestableConnectorOperationResponse.from(
                new ConnectorOperationOption("getPets", "GET", "/pets", "List pets", false));
        assertThat(response.operationId()).isEqualTo("getPets");
        assertThat(response.verb()).isEqualTo("GET");
        assertThat(response.path()).isEqualTo("/pets");
        assertThat(response.summary()).isEqualTo("List pets");
        assertThat(response.write()).isFalse();
    }
}
