package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Per-organization settings for the in-app documentation help chat agent (AF-901) — a singleton row
 * binding the agent to an {@code ai_config} plus its retrieval / conversation / retention tunables.
 *
 * <p>The agent is a documentation reader: it answers from AccessFlow's own bundled corpus and has no
 * tools and no data access. It can be enabled before RAG is configured — with retrieval off it
 * answers from a generated quick-reference block instead of retrieved sections.
 */
public interface HelpAgentConfigService {

    /**
     * The organization's configuration, or a defaulted view (with a {@code null} id) when no row has
     * been saved yet — never {@code null}, and never a "not found" failure.
     */
    HelpAgentConfigView getOrDefault(UUID organizationId);

    /**
     * Applies a partial update, creating the row on first write. Ranges are always validated;
     * enabling additionally validates that the bound configuration can actually answer.
     *
     * @throws HelpAgentConfigInvalidException a value is out of range, or enabling would produce a
     *                                         configuration that cannot answer
     * @throws HelpCorpusUnavailableException  enabling the agent when the bundled documentation
     *                                         corpus could not be loaded from the classpath
     * @throws AiConfigNotFoundException       {@code aiConfigId} is not an AI configuration of this
     *                                         organization
     */
    HelpAgentConfigView update(UUID organizationId, UpdateHelpAgentConfigCommand command);

    /**
     * Embeds a probe with the bound configuration's embedding model and searches its vector store.
     * Never throws for a configuration problem — the reason is carried in the result.
     */
    HelpAgentConnectionTestResult testConnection(UUID organizationId);

    /**
     * Requests a re-ingestion of the bundled documentation corpus for this organization. Accepted
     * asynchronously and forced — it ignores the "already at this corpus version" check, since an
     * admin pressing re-index has a reason that check cannot see. A no-op for an organization that
     * has never saved a configuration.
     */
    void requestReindex(UUID organizationId);
}
