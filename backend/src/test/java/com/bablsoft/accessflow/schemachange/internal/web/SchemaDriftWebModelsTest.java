package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftWebModelsTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private SchemaDriftScanView scanView() {
        return new SchemaDriftScanView(UUID.randomUUID(), orgId, pipelineId, environmentId,
                UUID.randomUUID(), SchemaDriftBaseline.PROMOTION_SNAPSHOT, NOW, NOW.plusSeconds(5),
                false, 3, true, "ENGINE_NOT_APPLICABLE");
    }

    @Test
    void theScanResponseCarriesEveryFieldAndDropsTheOrganization() {
        var view = scanView();

        var response = SchemaDriftScanResponse.from(view);

        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.pipelineId()).isEqualTo(pipelineId);
        assertThat(response.environmentId()).isEqualTo(environmentId);
        assertThat(response.datasourceId()).isEqualTo(view.datasourceId());
        assertThat(response.baseline()).isEqualTo(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        assertThat(response.startedAt()).isEqualTo(NOW);
        assertThat(response.finishedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(response.applicable()).isFalse();
        assertThat(response.findingsCount()).isEqualTo(3);
        assertThat(response.partial()).isTrue();
        assertThat(response.errorMessage()).isEqualTo("ENGINE_NOT_APPLICABLE");
    }

    @Test
    void theFindingResponseCarriesEveryFieldIncludingNulls() {
        var view = new SchemaDriftFindingView(UUID.randomUUID(), orgId, UUID.randomUUID(), environmentId,
                "public.orders", SchemaDriftFindingKind.MISSING_IN_TARGET, "orders (4 columns)", null,
                SchemaDriftFindingStatus.RESOLVED, NOW, NOW, NOW.plusSeconds(60));

        var response = SchemaDriftFindingResponse.from(view);

        assertThat(response.objectPath()).isEqualTo("public.orders");
        assertThat(response.findingKind()).isEqualTo(SchemaDriftFindingKind.MISSING_IN_TARGET);
        assertThat(response.expectedValue()).isEqualTo("orders (4 columns)");
        assertThat(response.actualValue()).isNull();
        assertThat(response.status()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
        assertThat(response.resolvedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void theConfigResponseKeepsANullIdForAnUnconfiguredPipeline() {
        var view = new SchemaDriftConfigView(null, orgId, pipelineId, false,
                SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null, 24, null, null);

        var response = SchemaDriftConfigResponse.from(view);

        assertThat(response.id()).isNull();
        assertThat(response.pipelineId()).isEqualTo(pipelineId);
        assertThat(response.enabled()).isFalse();
        assertThat(response.scanIntervalHours()).isEqualTo(24);
    }

    @Test
    void theScanPageResponseCarriesThePagingMetadata() {
        var page = SchemaDriftScanPageResponse.from(
                new PageResponse<>(List.of(scanView()), 2, 10, 25, 3));

        assertThat(page.content()).hasSize(1);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(25);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void theFindingPageResponseCarriesThePagingMetadata() {
        var view = new SchemaDriftFindingView(UUID.randomUUID(), orgId, UUID.randomUUID(), environmentId,
                "public.orders.email", SchemaDriftFindingKind.TYPE_MISMATCH, "text", "int4",
                SchemaDriftFindingStatus.OPEN, NOW, NOW, null);

        var page = SchemaDriftFindingPageResponse.from(new PageResponse<>(List.of(view), 0, 20, 1, 1));

        assertThat(page.content()).singleElement()
                .satisfies(row -> assertThat(row.actualValue()).isEqualTo("int4"));
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void anEmptyPageMapsToAnEmptyResponse() {
        assertThat(SchemaDriftScanPageResponse.from(PageResponse.empty(0, 20)).content()).isEmpty();
        assertThat(SchemaDriftFindingPageResponse.from(PageResponse.empty(0, 20)).content()).isEmpty();
    }

    @Test
    void theConfigRequestBecomesTheCommandVerbatim() {
        var request = new UpsertSchemaDriftConfigRequest(true, SchemaDriftBaseline.BASELINE_ENVIRONMENT,
                environmentId, 6);

        assertThat(request.toCommand())
                .extracting("enabled", "baseline", "baselineEnvironmentId", "scanIntervalHours")
                .containsExactly(true, SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId, 6);
    }
}
