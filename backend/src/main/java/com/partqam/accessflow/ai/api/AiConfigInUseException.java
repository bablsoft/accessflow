package com.partqam.accessflow.ai.api;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when an admin attempts to delete an {@code ai_config} row that is still bound to one or
 * more datasources. Carries the list of bound datasource references so the global handler can
 * surface them in the {@code ProblemDetail.metadata}. Resolved to HTTP 409 with
 * {@code error="AI_CONFIG_IN_USE"}.
 */
public class AiConfigInUseException extends RuntimeException {

    private final UUID aiConfigId;
    private final List<DatasourceRef> boundDatasources;

    public AiConfigInUseException(UUID aiConfigId, List<DatasourceRef> boundDatasources) {
        super("AI config " + aiConfigId + " is bound to " + boundDatasources.size() + " datasource(s)");
        this.aiConfigId = aiConfigId;
        this.boundDatasources = List.copyOf(boundDatasources);
    }

    public UUID aiConfigId() {
        return aiConfigId;
    }

    public List<DatasourceRef> boundDatasources() {
        return boundDatasources;
    }

    public record DatasourceRef(UUID id, String name) {
    }
}
