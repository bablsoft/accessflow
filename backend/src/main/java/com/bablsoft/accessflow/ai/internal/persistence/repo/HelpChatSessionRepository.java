package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface HelpChatSessionRepository extends JpaRepository<HelpChatSessionEntity, UUID> {

    /**
     * A session the given user owns. Every read and write of a transcript goes through this — a
     * conversation is private to the person who had it, so the id alone is never enough, and a
     * session belonging to someone else is simply not found rather than refused.
     */
    Optional<HelpChatSessionEntity> findByIdAndOrganizationIdAndUserId(UUID id,
                                                                       UUID organizationId,
                                                                       UUID userId);

    /**
     * Deletes the organization's conversations last touched before {@code cutoff} — the retention
     * job's whole unit of work, one statement per organization.
     *
     * <p>A never-used session falls back to {@code created_at}, so an empty conversation someone
     * opened and abandoned still ages out. Messages are removed by the {@code ON DELETE CASCADE} on
     * {@code help_chat_messages.session_id} rather than by JPA: a bulk delete loads no entity, which
     * is also what keeps the {@code @Version} column out of the way.
     *
     * @return how many sessions were deleted
     */
    @Modifying
    @Transactional
    @Query("delete from HelpChatSessionEntity s "
            + "where s.organizationId = :organizationId "
            + "and coalesce(s.lastMessageAt, s.createdAt) < :cutoff")
    int deleteByOrganizationIdAndLastActivityBefore(@Param("organizationId") UUID organizationId,
                                                    @Param("cutoff") Instant cutoff);
}
