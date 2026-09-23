package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;

import java.util.UUID;

/**
 * Everything one drift scan of one environment needs, resolved once before the scan opens (#881).
 * Resolving it up front is what lets the manual path answer its 404s before it writes anything.
 */
record SchemaDriftScanContext(UUID organizationId, UUID pipelineId, UUID environmentId,
                              UUID datasourceId, DbType dbType, SchemaDriftBaseline baseline,
                              UUID baselineEnvironmentId) {
}
