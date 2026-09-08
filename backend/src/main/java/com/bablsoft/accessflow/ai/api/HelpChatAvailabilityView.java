package com.bablsoft.accessflow.ai.api;

/**
 * Whether the in-app help agent can answer for one organization right now (AF-905).
 *
 * <p>Exists so a client can decide whether to show the launcher at all without POSTing a question and
 * reading a 409 off the floor. It is deliberately the thinnest possible answer: nothing here names an
 * AI provider, a model, an {@code ai_config}, or an index error, because every signed-in user may
 * read it and none of that is theirs to see.
 *
 * @param enabled         the agent is switched on and bound to an AI configuration, so a question
 *                        will be attempted. Rate limits and provider failures are still possible
 * @param retrievalActive answers will cite documentation sections. {@code false} is the supported
 *                        degraded mode (epic AF-899 decision 9), in which the agent answers from the
 *                        bundled quick-reference block and cites nothing
 * @param corpusVersion   the documentation revision this build ships, or empty when the bundle could
 *                        not be loaded
 * @param chunkCount      how many documentation chunks that bundle holds, or 0
 */
public record HelpChatAvailabilityView(
        boolean enabled,
        boolean retrievalActive,
        String corpusVersion,
        int chunkCount) {

    public HelpChatAvailabilityView {
        corpusVersion = corpusVersion == null ? "" : corpusVersion;
    }
}
