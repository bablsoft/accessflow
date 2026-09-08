package com.bablsoft.accessflow.ai.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One in-app help conversation (AF-904). Owns nothing but its own counters: the messages are a
 * separate table read in {@code created_at} order, deliberately not a mapped collection, so
 * appending a turn never loads the transcript so far and the retention job can delete a whole
 * conversation with one statement.
 *
 * <p>{@code title} is derived from the first question rather than supplied — nothing in the product
 * asks a user to name a conversation before starting one — and {@code messageCount} /
 * {@code lastMessageAt} are maintained by the service on every appended turn so a session list can
 * be rendered without counting messages per row.
 */
@Entity
@Table(name = "help_chat_sessions")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class HelpChatSessionEntity {

    /** Longest title the column holds; a derived title is truncated to it. */
    public static final int MAX_TITLE_LENGTH = 255;

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    /** When the last message landed; {@code null} for a session that was never used. */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
