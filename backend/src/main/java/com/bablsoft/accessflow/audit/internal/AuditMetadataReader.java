package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared read helpers for the JSONB {@code metadata} column of {@code audit_log}. Both audit-derived
 * aggregations (UBA baselines, AF-383; grant-usage summaries, #625) parse the same enriched keys, so
 * the parsing lives here once rather than being copied per consumer.
 *
 * <p>Every accessor is fail-soft by design: rows written before a given enrichment landed simply
 * yield null / empty, and a row whose metadata will not parse is skipped with a warning rather than
 * aborting a batch.
 */
final class AuditMetadataReader {

    private static final Logger log = LoggerFactory.getLogger(AuditMetadataReader.class);

    private AuditMetadataReader() {
    }

    /** The row's parsed metadata, or {@code null} when it is absent, blank, or unparseable. */
    static JsonNode parse(AuditLogEntity row, ObjectMapper objectMapper) {
        var raw = row.getMetadata();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (RuntimeException ex) {
            log.warn("Skipping audit row {} with unparseable metadata", row.getId());
            return null;
        }
    }

    /** A string field, or {@code null} when absent or not a string. */
    static String textOrNull(JsonNode metadata, String field) {
        var node = metadata.path(field);
        return node.isString() ? node.asString() : null;
    }

    /** A numeric field as a {@code Long}, or {@code null} when absent or not a number. */
    static Long longOrNull(JsonNode metadata, String field) {
        var node = metadata.path(field);
        return node.isNumber() ? node.asLong() : null;
    }

    /** The string elements of an array field; empty when the field is absent or not an array. */
    static List<String> stringArray(JsonNode metadata, String field) {
        var node = metadata.path(field);
        if (!node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>(node.size());
        node.forEach(element -> {
            if (element.isString()) {
                values.add(element.asString());
            }
        });
        return values;
    }
}
