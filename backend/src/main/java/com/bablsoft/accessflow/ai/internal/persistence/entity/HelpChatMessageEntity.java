package com.bablsoft.accessflow.ai.internal.persistence.entity;

import com.bablsoft.accessflow.ai.api.HelpChatRole;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One message of a help conversation (AF-904). Immutable once written — a transcript that could be
 * edited afterwards would not be a transcript — which is why there is no {@code @Version} and no
 * {@code updated_at} here.
 *
 * <p>{@code citations} is the JSON the server resolved from the chunks it actually retrieved for
 * that turn, stored as written and never re-derived from {@code content} (epic AF-899 decision 6).
 * A reloaded conversation therefore renders exactly the links it rendered live, even once the corpus
 * has moved on; {@code corpusVersion} records which documentation revision it moved on from.
 *
 * <p>{@code organizationId} is denormalised from the session on purpose: the monthly AI token budget
 * sums this table per organization on every AI call, and it should not have to join to do it.
 */
@Entity
@Table(name = "help_chat_messages")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class HelpChatMessageEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /**
     * Position in the conversation, from 1. The transcript's order — both messages of a turn are
     * written in one statement and share a {@code created_at}, so a timestamp sort could put the
     * answer before its question.
     */
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "help_chat_role")
    private HelpChatRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String citations = "[]";

    /** Corpus revision that produced an assistant answer; {@code null} on a user message. */
    @Column(name = "corpus_version", length = 64)
    private String corpusVersion;

    @Column(length = 100)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
