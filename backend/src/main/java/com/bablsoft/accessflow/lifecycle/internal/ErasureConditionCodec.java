package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Single boundary for the {@code conditions} JSONB wire shape shared by {@code retention_policies}
 * and {@code deletion_requests} (AF-519). Converts an {@link ErasureConditionSet} to/from the JSON
 * string stored in the JSONB column, reusing the injected {@link ObjectMapper} (so it inherits the
 * global SNAKE_CASE naming). Entities keep the raw JSON {@code String}; the codec converts at the
 * service boundary. Mirrors {@code workflow/internal/routing/RoutingConditionCodec}.
 */
@Component
public class ErasureConditionCodec {

    private final ObjectMapper mapper;

    public ErasureConditionCodec(ObjectMapper objectMapper) {
        this.mapper = objectMapper;
    }

    /** Serialise a condition set to the JSON string stored in the JSONB column ({@code null} → null). */
    public String toJson(ErasureConditionSet set) {
        if (set == null || set.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(set);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to serialize erasure conditions", ex);
        }
    }

    /** Deserialise the stored JSONB string back into a condition set ({@code null}/blank → null). */
    public ErasureConditionSet fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, ErasureConditionSet.class);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to deserialize erasure conditions", ex);
        }
    }
}
