package com.bablsoft.accessflow.ai.api;

/**
 * Answers one question about how to use AccessFlow from AccessFlow's own bundled documentation
 * (AF-903, epic AF-899).
 *
 * <p><strong>A documentation reader, not a data surface.</strong> It has no tools and no data access:
 * it cannot read a query, a result, an audit row, a schema or a datasource, and it cannot act. That
 * is what keeps a free-text model endpoint out of the governance path this product exists to enforce.
 *
 * <p>Every turn is synchronous, rate-limited twice (the organization's AI limit, then the
 * organization's per-user help limit), and capped server-side regardless of what the client sends.
 * Retrieval is best-effort: when the documentation index is unavailable, stale or returns nothing,
 * the agent answers from the bundled quick-reference block instead and cites nothing.
 *
 * <p>Persistence is deliberately not here. This service takes the history it is given and returns an
 * answer; storing the conversation is the caller's job (AF-904).
 */
public interface HelpChatService {

    /**
     * @throws HelpChatUnavailableException the organization's agent is off, unsaved, or bound to no
     *                                      AI configuration
     * @throws AiRateLimitExceededException the organization or the user is over their per-minute
     *                                      limit
     * @throws AiBudgetExceededException    the organization is over its monthly token budget
     * @throws AiAnalysisException          the provider call failed
     */
    HelpChatAnswer answer(HelpChatRequest request);
}
