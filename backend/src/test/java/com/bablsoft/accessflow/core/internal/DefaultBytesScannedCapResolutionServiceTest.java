package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction;
import com.bablsoft.accessflow.core.api.BytesScannedCapSource;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultBytesScannedCapResolutionServiceTest {

    @Mock DatasourceRepository datasourceRepository;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @InjectMocks DefaultBytesScannedCapResolutionService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void noCapAnywhereResolvesToNothing() {
        givenDatasource(null, BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        givenGrant(null);

        assertThat(service.resolve(datasourceId, userId)).isEmpty();
    }

    @Test
    void anUnknownDatasourceResolvesToNothing() {
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        assertThat(service.resolve(datasourceId, userId)).isEmpty();
        verifyNoInteractions(permissionLookupService);
    }

    @Test
    void theDatasourceCapAppliesWhenNoGrantSetsOne() {
        givenDatasource(1_000L, BytesCapMissingEstimateAction.REJECT);
        givenGrant(null);

        var cap = service.resolve(datasourceId, userId).orElseThrow();

        assertThat(cap.limit()).isEqualTo(1_000L);
        assertThat(cap.source()).isEqualTo(BytesScannedCapSource.DATASOURCE);
        assertThat(cap.missingEstimate()).isEqualTo(BytesCapMissingEstimateAction.REJECT);
    }

    @Test
    void aTighterGrantWins() {
        givenDatasource(1_000L, BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        givenGrant(400L);

        var cap = service.resolve(datasourceId, userId).orElseThrow();

        assertThat(cap.limit()).isEqualTo(400L);
        assertThat(cap.source()).isEqualTo(BytesScannedCapSource.GRANT);
    }

    @Test
    void aLooserGrantNeverWidensTheDatasourceCap() {
        givenDatasource(1_000L, BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        givenGrant(5_000L);

        var cap = service.resolve(datasourceId, userId).orElseThrow();

        assertThat(cap.limit()).isEqualTo(1_000L);
        assertThat(cap.source()).isEqualTo(BytesScannedCapSource.DATASOURCE);
    }

    @Test
    void aTieIsAttributedToTheDatasource() {
        givenDatasource(1_000L, BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        givenGrant(1_000L);

        assertThat(service.resolve(datasourceId, userId).orElseThrow().source())
                .isEqualTo(BytesScannedCapSource.DATASOURCE);
    }

    @Test
    void aGrantCapAppliesOnAnUncappedDatasourceWithItsMissingEstimatePolicy() {
        givenDatasource(null, BytesCapMissingEstimateAction.REJECT);
        givenGrant(300L);

        var cap = service.resolve(datasourceId, userId).orElseThrow();

        assertThat(cap.limit()).isEqualTo(300L);
        assertThat(cap.source()).isEqualTo(BytesScannedCapSource.GRANT);
        assertThat(cap.missingEstimate()).isEqualTo(BytesCapMissingEstimateAction.REJECT);
    }

    @Test
    void anAnonymousLookupOnlyReadsTheDatasource() {
        givenDatasource(1_000L, BytesCapMissingEstimateAction.REQUIRE_REVIEW);

        assertThat(service.resolve(datasourceId, null)).isPresent();
        verifyNoInteractions(permissionLookupService);
    }

    private void givenDatasource(Long cap, BytesCapMissingEstimateAction missing) {
        var entity = new DatasourceEntity();
        entity.setId(datasourceId);
        entity.setMaxBytesScannedPerQuery(cap);
        entity.setBytesCapMissingEstimate(missing);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
    }

    private void givenGrant(Long override) {
        var view = new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, true,
                false, false, false, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, override, null);
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.of(view));
    }
}
