package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
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
 * Immutable snapshot of an executed query (AF-449). Written once when a query transitions to
 * {@code EXECUTED} (on {@code QueryExecutedEvent}), capturing the exact sanitized SQL, the source
 * datasource's schema fingerprint, the AI verdict, and the approval decisions as they stood at
 * execution time. INSERT-only — never updated, so there is no {@code @Version} column.
 */
@Entity
@Table(name = "query_snapshots")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class QuerySnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "query_request_id", nullable = false, updatable = false)
    private UUID queryRequestId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "query_type", nullable = false, columnDefinition = "query_type")
    private QueryType queryType;

    @Column(name = "transactional", nullable = false)
    private boolean transactional = false;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "db_type", nullable = false, columnDefinition = "db_type")
    private DbType dbType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "referenced_tables", nullable = false, columnDefinition = "text[]")
    private String[] referencedTables = new String[0];

    @Column(name = "schema_hash", length = 64)
    private String schemaHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_analysis", columnDefinition = "jsonb")
    private String aiAnalysisJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_decisions", nullable = false, columnDefinition = "jsonb")
    private String reviewDecisionsJson = "[]";

    @Column(name = "rows_affected")
    private Long rowsAffected;

    @Column(name = "execution_duration_ms")
    private Integer executionDurationMs;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
