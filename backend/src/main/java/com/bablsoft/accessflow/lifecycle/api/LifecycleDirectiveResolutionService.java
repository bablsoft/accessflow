package com.bablsoft.accessflow.lifecycle.api;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.SoftDeleteDirective;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the read-time governance directives a datasource's enabled lifecycle policies contribute
 * to a query execution. Today this is read-time pseudonymization: each enabled {@code PSEUDONYMIZE}
 * retention policy yields a {@link ColumnMaskDirective} per target column, mapping its
 * {@link LifecycleTransform} onto the shared post-fetch masker (SHA256_SALTED / TOKENIZATION → a
 * salted SHA-256 with the per-org salt; FORMAT_PRESERVING → format-preserving). The proxy merges
 * these alongside the masking-policy directives — pseudonymization preserves row presence so
 * aggregates survive while PII is irreversibly transformed.
 */
public interface LifecycleDirectiveResolutionService {

    List<ColumnMaskDirective> resolveColumnMasks(UUID organizationId, UUID datasourceId);

    /**
     * Read-side soft-delete filters: an {@code IS_NULL} predicate on each enabled {@code SOFT_DELETE}
     * policy's marker column, so soft-deleted rows are invisible to SELECT/UPDATE/DELETE.
     */
    List<RowSecurityDirective> resolveSoftDeleteFilters(UUID organizationId, UUID datasourceId);

    /**
     * Write-side soft-delete rewrite signals: each enabled {@code SOFT_DELETE} policy turns a
     * {@code DELETE} against its table into an {@code UPDATE … SET marker = CURRENT_TIMESTAMP}.
     */
    List<SoftDeleteDirective> resolveSoftDeletes(UUID organizationId, UUID datasourceId);
}
