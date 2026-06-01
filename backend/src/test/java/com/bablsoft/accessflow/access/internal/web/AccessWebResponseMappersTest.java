package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestService.DatasourceOption;
import com.bablsoft.accessflow.access.api.AccessRequestView;
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
                UUID.randomUUID(), "db", true, false, true, List.of("public"), null, "PT4H", "j",
                AccessGrantStatus.PENDING, null, null, Instant.now(), Instant.now());
        var response = AccessRequestResponse.from(view);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.datasourceName()).isEqualTo("db");
        assertThat(response.canDdl()).isTrue();
        assertThat(response.status()).isEqualTo(AccessGrantStatus.PENDING);
    }

    @Test
    void accessRequestPageResponseFromMapsContent() {
        var view = new AccessRequestView(id, UUID.randomUUID(), UUID.randomUUID(), "u@x.io",
                UUID.randomUUID(), "db", true, false, false, null, null, "PT4H", "j",
                AccessGrantStatus.APPROVED, null, null, Instant.now(), Instant.now());
        var page = new PageResponse<>(List.of(view), 0, 20, 1, 1);
        var response = AccessRequestPageResponse.from(page);
        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void pendingAccessRequestItemFromMapsSummaries() {
        var datasourceId = UUID.randomUUID();
        var requesterId = UUID.randomUUID();
        var pending = new PendingAccessRequest(id, datasourceId, "db", requesterId, "u@x.io",
                true, false, false, List.of("public"), null, "PT4H", "j", 1, Instant.now());
        var item = PendingAccessRequestItem.from(pending);
        assertThat(item.datasource().id()).isEqualTo(datasourceId);
        assertThat(item.requestedBy().email()).isEqualTo("u@x.io");
        assertThat(item.currentStage()).isEqualTo(1);
    }

    @Test
    void pendingPageResponseFromMapsContent() {
        var pending = new PendingAccessRequest(id, UUID.randomUUID(), "db", UUID.randomUUID(),
                "u@x.io", true, false, false, null, null, "PT4H", "j", 0, Instant.now());
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
}
