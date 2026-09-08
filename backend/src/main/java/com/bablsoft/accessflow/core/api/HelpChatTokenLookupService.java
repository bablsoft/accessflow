package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * How many tokens an organization's in-app help chat has spent since a given instant (#904, epic
 * #899 decision 10).
 *
 * <p>Declared in {@code core.api} and implemented by the {@code ai} module, which owns
 * {@code help_chat_messages} — the standard inversion (`ai -> core` already exists; the reverse would
 * be a cycle). It exists so the monthly token budget, which lives in {@code core}, can add help spend
 * without {@code core} querying a table it does not own: a schema change in {@code ai} then breaks a
 * compile in {@code ai}, and the module boundary stays visible to {@code ApplicationModulesTest}.
 *
 * <p>Separate from the {@code ai_analyses} sum because help turns deliberately write no
 * {@code ai_analyses} row — that table backs the admin AI-analyses history page. The tokens come off
 * the same provider key, so the budget adds both.
 */
public interface HelpChatTokenLookupService {

    /**
     * @param organizationId required; scopes the sum to this organization's help conversations
     * @param since          inclusive lower bound on when the message was recorded
     * @return summed prompt + completion tokens, or 0 when there are no matching messages
     */
    long sumTokensSince(UUID organizationId, Instant since);
}
