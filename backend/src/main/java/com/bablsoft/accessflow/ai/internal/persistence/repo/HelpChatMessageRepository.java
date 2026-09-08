package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HelpChatMessageRepository extends JpaRepository<HelpChatMessageEntity, UUID> {

    /**
     * One conversation in the order it happened, backed by
     * {@code help_chat_messages_session_sequence_idx}.
     */
    List<HelpChatMessageEntity> findBySessionIdOrderBySequenceNumberAsc(UUID sessionId);
}
