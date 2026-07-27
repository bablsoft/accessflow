package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.discovery.api.DiscoveryDecision;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingService.BulkDecisionOutcome;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingView;
import com.bablsoft.accessflow.discovery.api.DiscoveryRowStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanConfigView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryWebModelsTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    @Test
    void configResponseMirrorsView() {
        var dsId = UUID.randomUUID();
        var response = DiscoveryConfigResponse.from(new DiscoveryScanConfigView(dsId, true, 200,
                12, true, NOW, "partial"));

        assertThat(response.datasourceId()).isEqualTo(dsId);
        assertThat(response.enabled()).isTrue();
        assertThat(response.sampleSize()).isEqualTo(200);
        assertThat(response.scanIntervalHours()).isEqualTo(12);
        assertThat(response.aiClassificationEnabled()).isTrue();
        assertThat(response.lastScanAt()).isEqualTo(NOW);
        assertThat(response.lastScanError()).isEqualTo("partial");
    }

    @Test
    void updateRequestMapsToCommand() {
        var command = new UpdateDiscoveryConfigRequest(true, 300, 48, false).toCommand();

        assertThat(command.enabled()).isTrue();
        assertThat(command.sampleSize()).isEqualTo(300);
        assertThat(command.scanIntervalHours()).isEqualTo(48);
        assertThat(command.aiClassificationEnabled()).isFalse();
    }

    @Test
    void findingPageResponseMapsContentAndPaging() {
        var view = findingView();
        var page = DiscoveryFindingPageResponse.from(
                new PageResponse<>(List.of(view), 1, 20, 41, 3));

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
        var response = page.content().getFirst();
        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.schemaName()).isEqualTo("public");
        assertThat(response.tableName()).isEqualTo("users");
        assertThat(response.columnName()).isEqualTo("email");
        assertThat(response.classification()).isEqualTo(DataClassification.PII);
        assertThat(response.detector()).isEqualTo(DiscoveryDetector.EMAIL);
        assertThat(response.confidence()).isEqualTo(96);
        assertThat(response.sampleRedacted()).isEqualTo("****@x.com");
        assertThat(response.status()).isEqualTo(DiscoveryFindingStatus.PENDING);
    }

    @Test
    void bulkResponseMapsRowsWithoutFindingDetails() {
        var findingId = UUID.randomUUID();
        var outcome = new BulkDecisionOutcome(List.of(
                new BulkDecisionOutcome.Row(findingId, DiscoveryRowStatus.TAG_CONFLICT,
                        DiscoveryFindingStatus.CONFIRMED, findingView()),
                new BulkDecisionOutcome.Row(findingId, DiscoveryRowStatus.NOT_FOUND, null, null)));

        var response = BulkDiscoveryDecisionResponse.from(outcome);

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().getFirst().status())
                .isEqualTo(DiscoveryRowStatus.TAG_CONFLICT);
        assertThat(response.results().getFirst().newStatus())
                .isEqualTo(DiscoveryFindingStatus.CONFIRMED);
        assertThat(response.results().getLast().status()).isEqualTo(DiscoveryRowStatus.NOT_FOUND);
        assertThat(response.results().getLast().newStatus()).isNull();
    }

    @Test
    void bulkRequestHoldsDecision() {
        var request = new BulkDiscoveryDecisionRequest(List.of(UUID.randomUUID()),
                DiscoveryDecision.DISMISS);

        assertThat(request.decision()).isEqualTo(DiscoveryDecision.DISMISS);
        assertThat(request.findingIds()).hasSize(1);
    }

    private static DiscoveryFindingView findingView() {
        return new DiscoveryFindingView(UUID.randomUUID(), "public", "users", "email",
                DataClassification.PII, DiscoveryDetector.EMAIL, 96, "****@x.com", null, 48, 50,
                DiscoveryFindingStatus.PENDING, NOW, NOW, null, null);
    }
}
