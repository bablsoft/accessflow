package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.DataClassification;

import java.util.List;
import java.util.UUID;

/**
 * Optional AI pass of the sensitive-data discovery scan (AF-623). Classifies a table's columns
 * using the organization's first usable {@code ai_config}, complementing the local regex/checksum
 * detectors (e.g. a {@code national_id} column no regex covers).
 *
 * <p><strong>Privacy:</strong> callers must pass only redacted sample values (format-preserving
 * masking) — raw sampled data never reaches the AI provider.
 *
 * <p><strong>Fail-safe by contract:</strong> returns an empty list when the org has no usable AI
 * config, the provider errors, or the response is not the expected JSON — it never throws and
 * never blocks the scan (same posture as the UBA anomaly summary, AF-383).
 */
public interface DataDiscoveryAiService {

    List<DiscoveryColumnSuggestion> classifyColumns(UUID organizationId,
                                                    DiscoveryTableContext context);

    /** One table's worth of column metadata + redacted samples. */
    record DiscoveryTableContext(String tableName, List<DiscoveryColumnContext> columns) {

        public DiscoveryTableContext {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /** {@code redactedSamples} must already be redacted by the caller. */
    record DiscoveryColumnContext(String name, String type, List<String> redactedSamples) {

        public DiscoveryColumnContext {
            redactedSamples = redactedSamples == null ? List.of() : List.copyOf(redactedSamples);
        }
    }

    /** A proposed classification for one column; {@code confidence} is clamped to 0–100. */
    record DiscoveryColumnSuggestion(String columnName, DataClassification classification,
                                     int confidence, String rationale) {
    }
}
