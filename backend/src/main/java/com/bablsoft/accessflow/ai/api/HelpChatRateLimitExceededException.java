package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when one user asks help questions faster than their organization's
 * {@code help_agent_config.per_user_requests_per_minute} allows (AF-903).
 *
 * <p>Extends {@link AiRateLimitExceededException} so nothing that already handles the organization-wide
 * limit has to learn about this one, but it is a distinct type because it is a distinct fact: the
 * organization's AI budget is fine and the user is simply asking too fast. The chat surface maps it to
 * its own {@code HELP_CHAT_RATE_LIMITED} code so a panel can say "slow down" rather than "your
 * organization has run out of AI".
 */
public class HelpChatRateLimitExceededException extends AiRateLimitExceededException {

    public HelpChatRateLimitExceededException(int limit, long retryAfterSeconds) {
        super(limit, retryAfterSeconds);
    }
}
