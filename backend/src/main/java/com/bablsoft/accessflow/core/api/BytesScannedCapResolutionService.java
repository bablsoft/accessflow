package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the bytes-scanned cap (#941) that binds a user on a datasource. Empty when neither the
 * datasource nor any of the user's grants sets one.
 */
public interface BytesScannedCapResolutionService {

    Optional<AppliedBytesCap> resolve(UUID datasourceId, UUID userId);
}
