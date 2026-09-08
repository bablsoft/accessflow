package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HelpChatMessageRepository extends JpaRepository<HelpChatMessageEntity, UUID> {

    /**
     * One conversation in the order it happened, backed by
     * {@code help_chat_messages_session_sequence_idx}.
     */
    List<HelpChatMessageEntity> findBySessionIdOrderBySequenceNumberAsc(UUID sessionId);

    /**
     * Tokens the organization's help chat spent since {@code since} — the half of the monthly AI
     * budget that does not come from {@code ai_analyses}.
     *
     * <p>{@code organization_id} is denormalised onto the message precisely so this needs no join,
     * and it is backed by {@code help_chat_messages_organization_created_idx}. The coalesces keep the
     * result 0 rather than null for an organization with no messages, and treat a user message —
     * which reports no tokens because it cost none — as zero rather than as a null that would void
     * the sum.
     */
    @Query("select coalesce(sum(coalesce(m.promptTokens, 0) + coalesce(m.completionTokens, 0)), 0) "
            + "from HelpChatMessageEntity m "
            + "where m.organizationId = :organizationId and m.createdAt >= :since")
    long sumTokensSince(@Param("organizationId") UUID organizationId,
                        @Param("since") Instant since);
}
