package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored help conversation, without its messages (AF-904).
 *
 * @param id             the session
 * @param organizationId owning organization
 * @param userId         the person who had the conversation; nobody else can read it
 * @param title          derived from the first question asked, or empty for a session never used
 * @param messageCount   messages stored so far — two per completed turn
 * @param lastMessageAt  when the last message landed, or {@code null} for a session never used
 * @param createdAt      when the conversation was started
 */
public record HelpChatSessionView(
        UUID id,
        UUID organizationId,
        UUID userId,
        String title,
        int messageCount,
        Instant lastMessageAt,
        Instant createdAt) {
}
