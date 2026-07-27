package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.discovery.api.UpsertDiscoveryConfigCommand;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDiscoveryConfigServiceTest {

    @Mock
    private DiscoveryScanConfigRepository configRepository;
    @Mock
    private DatasourceAdminService datasourceAdminService;

    private final UUID dsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private DefaultDiscoveryConfigService service() {
        return new DefaultDiscoveryConfigService(configRepository, datasourceAdminService);
    }

    private DiscoveryScanConfigEntity existingConfig() {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setDatasourceId(dsId);
        config.setEnabled(true);
        config.setSampleSize(200);
        config.setScanIntervalHours(12);
        config.setAiClassificationEnabled(true);
        config.setLastScanAt(Instant.parse("2026-07-26T00:00:00Z"));
        config.setLastScanError("partial");
        return config;
    }

    @Test
    void getSynthesizesDefaultsWhenNoRowExists() {
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.empty());

        var view = service().get(dsId, orgId);

        assertThat(view.datasourceId()).isEqualTo(dsId);
        assertThat(view.enabled()).isFalse();
        assertThat(view.sampleSize()).isEqualTo(100);
        assertThat(view.scanIntervalHours()).isEqualTo(24);
        assertThat(view.aiClassificationEnabled()).isFalse();
        assertThat(view.lastScanAt()).isNull();
        assertThat(view.lastScanError()).isNull();
    }

    @Test
    void getReturnsPersistedRow() {
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(existingConfig()));

        var view = service().get(dsId, orgId);

        assertThat(view.enabled()).isTrue();
        assertThat(view.sampleSize()).isEqualTo(200);
        assertThat(view.scanIntervalHours()).isEqualTo(12);
        assertThat(view.aiClassificationEnabled()).isTrue();
        assertThat(view.lastScanError()).isEqualTo("partial");
    }

    @Test
    void getValidatesDatasourceOwnership() {
        when(datasourceAdminService.getForAdmin(dsId, orgId))
                .thenThrow(new DatasourceNotFoundException(dsId));

        assertThatThrownBy(() -> service().get(dsId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(configRepository, never()).findByDatasourceIdAndOrganizationId(any(), any());
    }

    @Test
    void upsertCreatesRowWithCommandValues() {
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.empty());
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service().upsert(dsId, orgId,
                new UpsertDiscoveryConfigCommand(true, 300, 48, true));

        var captor = ArgumentCaptor.forClass(DiscoveryScanConfigEntity.class);
        verify(configRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getDatasourceId()).isEqualTo(dsId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getSampleSize()).isEqualTo(300);
        assertThat(saved.getScanIntervalHours()).isEqualTo(48);
        assertThat(saved.isAiClassificationEnabled()).isTrue();
        assertThat(view.enabled()).isTrue();
    }

    @Test
    void upsertKeepsCurrentValuesForNullFields() {
        var existing = existingConfig();
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service().upsert(dsId, orgId,
                new UpsertDiscoveryConfigCommand(false, null, null, null));

        assertThat(view.enabled()).isFalse();
        assertThat(view.sampleSize()).isEqualTo(200);
        assertThat(view.scanIntervalHours()).isEqualTo(12);
        assertThat(view.aiClassificationEnabled()).isTrue();
    }
}
