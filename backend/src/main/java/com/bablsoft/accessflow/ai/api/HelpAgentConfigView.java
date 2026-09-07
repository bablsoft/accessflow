package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for {@link HelpAgentConfigService} — the in-app documentation help agent's settings for
 * one organization (AF-901). An organization that has never saved a row is served a defaulted view
 * with a {@code null} {@code id}, so callers never have to distinguish "not configured" from
 * "missing".
 *
 * <p>{@code indexedCorpusVersion} / {@code indexedAt} / {@code indexError} are ingestion state
 * written by the indexer, never by the admin API.
 */
public record HelpAgentConfigView(
        UUID id,
        UUID organizationId,
        boolean enabled,
        UUID aiConfigId,
        boolean retrievalEnabled,
        int topK,
        double similarityThreshold,
        int maxHistoryTurns,
        int maxQuestionChars,
        boolean sendUserContext,
        int retentionDays,
        int perUserRequestsPerMinute,
        String indexedCorpusVersion,
        Instant indexedAt,
        String indexError,
        Instant createdAt,
        Instant updatedAt) {
}
