package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;

/**
 * One operation-level difference between two parsed catalogs. {@code before} is {@code null} for
 * {@link ApiSchemaDriftKind#OPERATION_ADDED}, {@code after} is {@code null} for
 * {@link ApiSchemaDriftKind#OPERATION_REMOVED}; both are set for every other kind.
 */
record OperationDiffFinding(
        ApiSchemaDriftKind kind,
        String operationId,
        ApiOperation before,
        ApiOperation after) {
}
