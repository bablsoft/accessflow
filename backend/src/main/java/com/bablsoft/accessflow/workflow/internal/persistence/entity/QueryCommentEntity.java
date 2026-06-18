package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.workflow.api.CommentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * An inline collaboration comment anchored to a line range of a query's SQL. A thread is a root
 * comment ({@code parentCommentId == null}) plus replies pointing at the root. {@code status} is
 * meaningful only on the root. FK columns are bare UUIDs (not {@code @ManyToOne} into
 * {@code core.internal}) to respect the Modulith boundary.
 */
@Entity
@Table(name = "query_comments")
@Getter
@Setter
@NoArgsConstructor
public class QueryCommentEntity {

    @Id
    private UUID id;

    @Column(name = "query_request_id", nullable = false)
    private UUID queryRequestId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "parent_comment_id")
    private UUID parentCommentId;

    @Column(name = "anchor_start_line", nullable = false)
    private int anchorStartLine;

    @Column(name = "anchor_end_line", nullable = false)
    private int anchorEndLine;

    @Column(name = "anchor_snapshot", columnDefinition = "TEXT")
    private String anchorSnapshot;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", nullable = false, columnDefinition = "comment_status")
    private CommentStatus status = CommentStatus.OPEN;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
