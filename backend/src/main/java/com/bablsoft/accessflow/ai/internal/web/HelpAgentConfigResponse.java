package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpAgentConfigView;

import java.time.Instant;
import java.util.UUID;

/**
 * Help-agent settings as returned to the admin UI. {@code id} is {@code null} for an organization
 * that has never saved a row — the rest are the defaults it would get on first write.
 */
record HelpAgentConfigResponse(
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

    static HelpAgentConfigResponse from(HelpAgentConfigView view) {
        return new HelpAgentConfigResponse(
                view.id(),
                view.organizationId(),
                view.enabled(),
                view.aiConfigId(),
                view.retrievalEnabled(),
                view.topK(),
                view.similarityThreshold(),
                view.maxHistoryTurns(),
                view.maxQuestionChars(),
                view.sendUserContext(),
                view.retentionDays(),
                view.perUserRequestsPerMinute(),
                view.indexedCorpusVersion(),
                view.indexedAt(),
                view.indexError(),
                view.createdAt(),
                view.updatedAt());
    }
}
