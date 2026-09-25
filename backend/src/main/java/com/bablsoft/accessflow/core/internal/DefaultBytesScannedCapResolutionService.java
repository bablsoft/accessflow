package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AppliedBytesCap;
import com.bablsoft.accessflow.core.api.BytesScannedCapResolutionService;
import com.bablsoft.accessflow.core.api.BytesScannedCapSource;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The most restrictive of the datasource's {@code max_bytes_scanned_per_query} and the user's merged
 * grant override (#941). On a tie the datasource is named as the source — it is the setting an
 * admin would have to change for the limit to move.
 */
@Service
@RequiredArgsConstructor
class DefaultBytesScannedCapResolutionService implements BytesScannedCapResolutionService {

    private final DatasourceRepository datasourceRepository;
    private final DatasourceUserPermissionLookupService permissionLookupService;

    @Override
    @Transactional(readOnly = true)
    public Optional<AppliedBytesCap> resolve(UUID datasourceId, UUID userId) {
        var datasource = datasourceRepository.findById(datasourceId).orElse(null);
        if (datasource == null) {
            return Optional.empty();
        }
        var datasourceCap = datasource.getMaxBytesScannedPerQuery();
        var grantCap = userId == null ? null : permissionLookupService.findFor(userId, datasourceId)
                .map(DatasourceUserPermissionView::bytesScannedLimitOverride)
                .orElse(null);
        if (datasourceCap == null && grantCap == null) {
            return Optional.empty();
        }
        var missing = datasource.getBytesCapMissingEstimate();
        if (grantCap != null && (datasourceCap == null || grantCap < datasourceCap)) {
            return Optional.of(new AppliedBytesCap(grantCap, BytesScannedCapSource.GRANT, missing));
        }
        return Optional.of(new AppliedBytesCap(datasourceCap, BytesScannedCapSource.DATASOURCE,
                missing));
    }
}
