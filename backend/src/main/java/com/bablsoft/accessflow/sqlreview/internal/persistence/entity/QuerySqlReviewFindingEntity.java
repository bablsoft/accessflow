package com.bablsoft.accessflow.sqlreview.internal.persistence.entity;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
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
 * A persisted rule violation on a submitted query (#861). Immutable once written; cascades away
 * with its {@code query_requests} row. The query is a bare id (the cross-module convention) and
 * the message is never stored — {@code rule_id} + {@code args} are rendered per reader's locale.
 */
@Entity
@Table(name = "query_sql_review_findings")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class QuerySqlReviewFindingEntity {

    @Id
    private UUID id;

    @Column(name = "query_request_id", nullable = false, updatable = false)
    private UUID queryRequestId;

    @Column(name = "rule_id", nullable = false, length = 100)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "sql_review_severity")
    private SqlReviewSeverity severity;

    @Column(name = "statement_index", nullable = false)
    private int statementIndex = 0;

    @Column(name = "line_number")
    private Integer lineNumber;

    /** Message arguments as a JSON object, or null when the rule's message takes none. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String args;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
