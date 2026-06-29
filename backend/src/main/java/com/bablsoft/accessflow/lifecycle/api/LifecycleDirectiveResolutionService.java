package com.bablsoft.accessflow.lifecycle.api;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;

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
}
